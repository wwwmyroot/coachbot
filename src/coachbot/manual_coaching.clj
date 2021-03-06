;
; Copyright (c) 2017, Courage Labs, LLC.
;
; This file is part of CoachBot.
;
; CoachBot is free software: you can redistribute it and/or modify
; it under the terms of the GNU Affero General Public License as published by
; the Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
;
; CoachBot is distributed in the hope that it will be useful,
; but WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
; GNU Affero General Public License for more details.
;
; You should have received a copy of the GNU Affero General Public License
; along with CoachBot.  If not, see <http://www.gnu.org/licenses/>.
;

(ns coachbot.manual-coaching
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :as pprint]
            [coachbot.channel-coaching-process :as ccp]
            [coachbot.coaching-process :as cp]
            [coachbot.db :as db]
            [coachbot.hsql-utils :as hu]
            [coachbot.slack :as slack]
            [coachbot.storage :as storage]
            [honeysql.core :as sql]
            [honeysql.helpers :as h]
            [linked.core :as linked]
            [taoensso.timbre :as log])
  (:import [java.io FileOutputStream]))

(defn list-answers
  ([max-days-back]
   (list-answers max-days-back nil))
  ([max-days-back user-id]
   (let [ds (db/datasource)

         when-clause
         (sql/raw
           (format "timestampdiff(DAY, qa.created_date, now()) < %d"
                   max-days-back))

         latest-answers-query
         (-> (h/select [(sql/raw "date_format(qa.created_date, '%Y-%m-%d %h')")
                        :date]
                       [:st.id :team_id] [:scu.id :uid]
                       :scu.name
                       :bq.question
                       [:cq.question :cquestion]
                       [:qa.answer :qa])
             (h/from [:question_answers :qa])
             (h/join [:slack_coaching_users :scu]
                     [:= :scu.id :qa.slack_user_id]
                     [:slack_teams :st]
                     [:= :st.id :scu.team_id])
             (h/left-join [:base_questions :bq]
                          [:= :bq.id :qa.question_id]
                          [:custom_questions :cq]
                          [:= :cq.id :qa.cquestion_id])
             (h/where
               (if user-id [:and when-clause [:= :scu.id user-id]]
                           when-clause))
             (h/order-by :st.id :scu.id :qa.created_date))]

     (map #(let [{:keys [date team_id uid name question cquestion qa]} %
                 q (or question cquestion)
                 ty (if cquestion "c" "b")]
             (linked/map :d date :t team_id :u uid :n name :ty ty :q q
                         :a qa))
          (hu/query latest-answers-query ds))))
  ([] (list-answers 5)))

(defn count-engaged
  ([days]
   "Counts the number of users that have answered a question in X Days"
   (-> (h/select (sql/raw "count(distinct scu.id) AS num_users"))
       (h/from [:slack_coaching_users :scu])
       (h/join [:question_answers :qa]
               [:= :scu.id :qa.slack_user_id])
       (h/where (sql/raw
                  (format "timestampdiff(DAY, qa.created_date, now()) < %d"
                          days)))
       (hu/query (db/datasource))
       first
       :num_users))
  ([] (count-engaged 7)))

(defn register-custom-question! [team-id user-id question]
  (let [ds (db/datasource)
        user-info-query
        (-> (h/select [:scu.remote_user_id :uid] [:st.team_id :tid])
            (h/from [:slack_coaching_users :scu])
            (h/join [:slack_teams :st]
                    [:= :scu.team_id :st.id])
            (h/where [:and [:= :st.id team-id] [:= :scu.id user-id]]))

        [{:keys [uid tid]}] (hu/query user-info-query ds)
        [{:keys [generated_key]}]
        (cp/register-custom-question! tid uid question)]
    {:uid uid :tid tid :question-id generated_key}))

(defn send-custom-question-now! [team-id user-id question]
  (let [{:keys [uid tid]} (register-custom-question! team-id user-id question)]
    (cp/next-question! tid uid uid)))

(defn delete-custom-question! [question-id]
  (jdbc/with-db-transaction
    [conn (db/datasource)]
    (first (jdbc/delete! conn :custom_questions ["id = ?" question-id]))))

(defn send-message-to-active-coaching-users! [message]
  (doseq [{:keys [remote_user_id bot_access_token]}
          (-> (h/select :scu.remote_user_id :st.bot_access_token)
              (h/from [:slack_coaching_users :scu])
              (h/join [:slack_teams :st]
                      [:= :st.id :scu.team-id])
              (h/where [:= :active true])
              (hu/query (db/datasource)))]
    (slack/send-message! bot_access_token remote_user_id message)))

(defn count-active []
  (-> (h/select (sql/raw "count(distinct scu.id) AS users"))
      (h/from [:slack_coaching_users :scu])
      (h/where [:= :active true])
      (hu/query (db/datasource))
      first
      :users))

(defn count-active-group-users []
  (-> (h/select (sql/raw "count(distinct scu_id) AS group_users"))
      (h/from [:scu_question_groups :qg])
      (h/join [:slack_coaching_users :scu]
              [:= :scu.id :qg.scu_id])
      (h/where [:= :scu.active true])
      (hu/query (db/datasource))
      first
      :group_users))

(defn show-groups-by-user []
  (-> (h/select [:scu.id :scu-id] [:sqg.question_group_id :qg-id]
                [:qg.group_name :group-name] [:scu.team_id :team-id]
                [:scu.name :name])
      (h/from [:scu_question_groups :sqg])
      (h/join [:slack_coaching_users :scu]
              [:= :sqg.scu_id :scu.id]
              [:question_groups :qg]
              [:= :qg.id :sqg.question_group_id])
      (h/where [:= :scu.active true])
      (h/order-by :scu.id)
      (hu/query (db/datasource))))

(defn count-question-answers
  ([days]
   (-> (h/select (sql/raw "count(qa.id) AS answers"))
       (h/from [:question_answers :qa])
       (h/where (sql/raw
                  (format "timestampdiff(DAY, qa.created_date, now()) < %d"
                          days)))
       (hu/query (db/datasource))
       first
       :answers))
  ([] (count-question-answers 7)))

(defn answers-by-users
  ([days]
   (-> (h/select (sql/raw "count(qa.id) AS answers, scu.id, scu.name"))
       (h/from [:question_answers :qa])
       (h/join [:slack_coaching_users :scu]
               [:= :qa.slack_user_id :scu.id])
       (h/where (sql/raw
                  (format "timestampdiff(DAY, qa.created_date, now()) < %d"
                          days)))
       (h/group :qa.slack_user_id)
       (h/order-by [:1 :desc])
       (hu/query (db/datasource))))
  ([] (answers-by-users 7)))

(comment
  "This is the work area for coaches, for now. You'll need the following
   env variables set for your REPL pulled from 'heroku config':
     DB_TYPE, DB_URL, DB_USER, DB_PASS, SLACK_CLIENT_ID, SLACK_CLIENT_SECRET

   And DB_MAX_CONN=2
   And DB_CONN_TIMEOUT=600000"

  ;;----------------------  Basics & Bookkeeping ------------------------------

  ;; Switch to manual-coaching namespace in the REPL
  (in-ns 'coachbot.manual-coaching)

  ;; Get rid of annoying logging
  (log/set-level! :warn)

  ;; Print last stack trace
  (clojure.stacktrace/print-cause-trace *e)

  ;;----------------------  Individual Coaching  -----------------------------

  ;; List all active users
  (pprint/print-table (-> (h/select [:st.id :tid] [:st.team_id :slack_team_id]
                                    [:scu.id :uid]
                                    [:scu.remote_user_id :slack_user_id]
                                    :scu.name :scu.first_name)
                          (h/from [:slack_coaching_users :scu])
                          (h/where [:= :active true])
                          (h/join [:slack_teams :st]
                                  [:= :st.id :scu.team_id])
                          (hu/query (db/datasource))))

  ;; Use this to see the last X days of answers
  (pprint/print-table (list-answers 7))
  (pprint/print-table (list-answers 30 10))

  ;;Common Messages to send
  (def usage-checkin
    (str
      "Just wanted to check in.  I noticed that you haven't had "
      "a chance to use CoachBot much recently.  I was just curious if "
      "you have just been busy in the new year or if something else is "
      "annoying you about coachbot"))

  (def a-custom-question
    (str
      "You've been engaging with me (CoachBot) quite a bit recently. How is "
      "this working out for you?/nWhat's working well?/nWhat could be "
      "more helpful?"))

  ;; Use this to register a custom question.
  (let [team-id 3
        user-id 17
        question a-custom-question]
    (register-custom-question! team-id user-id question))

  ;; Register an array of custom questions
  (def custom-question-list
    ["How do you feel today?
    What is affecting you?
    What is going on in yourlife?"
     "What do you feel like doing today?"
     "What are your tasks today?"
     "Who should you proactively communicate with?"
     "What do you want to learn today?"
     "What did you learn from yesterday?
     What actions, if any, can you apply this learning to?"])

  (map #(register-custom-question! 3 12 %1) custom-question-list)

  ;; Use this to send a custom question immediately.
  (let [team-id 3
        user-id 18
        question oops]
    (send-custom-question-now! team-id user-id question))

  ;; In case you made a mistake, you can delete a question using the ID that
  ;; the above command returned.
  (delete-custom-question! 70)

  ;; Send a message to a client without logging a question (in case you want
  ;; to notify them of something but you're not expecting a response)
  (def my-message
    (str "OK. I've gone ahead and changed the groups "
         "that you're a part of to hopefully better reflect the questions "
         "that you like.  If you'd like to see what groups you're now in "
         "say _show groups_ and if you want to change things say "
         "_remove groups or _add groups_. Or, if you can't remember, just "
         "say _help_ and you'll get a bunch more information"))

  (cp/with-sending-constructs {:user-id "U3E7Z77B4" :team-id "T04SG55UA"
                               :channel nil} [ds send-fn _]
                              (send-fn my-message))

  ;; Show a bunch of data about a single slack coaching user
  (pprint/print-table
    (jdbc/query
      (db/datasource)
      [(str "select *, "
            "timestampdiff(HOUR, last_question_date, current_timestamp()) AS
            hours_since, "
            "current_timestamp as now "
            "from slack_coaching_users where email = ?")
       "travis@couragelabs.com"]))

  (pprint/print-table (show-groups-by-user))

  ;; Add a user to question groups
  ;; CoachBot Feedback, Collaboration/Teamwork, Decision Making, Emotional
  ;; Intelligence, Habits, Leadership, Learning, Management, Pause, Planning,
  ;; Reflection, Strengths, Time Management
  (def question-groups-to-add ["CoachBot Feedback"
                               "Collaboration/Teamwork"
                               "Decision Making"
                               "Emotional Intelligence"
                               "Habits"
                               "Leadership"
                               "Learning"
                               "Management"
                               "Pause"
                               "Planning"
                               "Reflection"
                               "Strengths"
                               "Time Management"])

  (map #(storage/add-to-question-group! (db/datasource) "U04T4P88M" %1)
       question-groups-to-add)

  ;; ----------------------  Team Coaching -----------------------------------

  ;; List all the teams
  (pprint/print-table (-> (h/select :*)
                          (h/from :slack_teams)
                          (hu/query (db/datasource))))

  ;; List all active channels (channels we're coaching)
  (pprint/print-table
    (-> (h/select :st.team_id :scc.channel_id :st.team_name
                  :scc.channel_name)
        (h/from storage/slack-coaching-channels)
        (h/join [:slack_teams :st]
                [:= :st.id :scc.team_id])
        (h/where [:= :active true])
        (hu/query (db/datasource))))

  ;; Find the results of the questions that expired recently
  (pprint/print-table
    (ccp/get-results-for-channel-questions! (t/days 5)))

  ;; Reset a question to send its results again
  (-> (h/update :channel-questions-asked)
      (h/sset {:delivered false})
      (h/where [:= :id 17])
      (hu/execute-safely! (db/datasource)))

  ;; Render a box plot for data to local disk
  (with-open [out (FileOutputStream. "/tmp/test.png")]
    (ccp/render-plot-for-channel-question! out 17))

  ;; Send a coaching question to a channel
  (ccp/send-channel-question!
    "T04SG55UA" "C3F03UHS6" "Highly Inaccurate" "Highly Accurate" 5 false
    "I feel excited about supporting the Courage Labs purpose" (t/days 1))

  (ccp/schedule-message! "T04SG55UA" "C3F03UHS6"
                         "Hey <!here|here> don't forget to answer the question!"
                         (t/plus (t/now) (t/minutes 1)))

  (pprint/print-table (-> (h/select :*) (h/from :queued_messages)
                          (hu/query (db/datasource))))

  ;; ----------------------  Stats and Data   ---------------------------

  ;; Count Number of engaged users
  (map count-engaged [7 14 30 60])

  ;; Count # of users using categories
  (count-active)
  (count-active-group-users)

  ;; List total number of questions answered over various time frames
  (map count-question-answers [7 14 30 60])

  ;; Questions answered by most active user over various time frames
  (map #(-> (answers-by-users %)
            (first)
            (select-keys [:answers :name]))
       [7 14 30 60])
  )
