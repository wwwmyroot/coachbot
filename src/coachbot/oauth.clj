;
; Copyright (c) 2016, Courage Labs, LLC.
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

(ns coachbot.oauth
  (:require [coachbot.slack :as slack]
            [compojure.api.sweet :refer :all]
            [ring.util.http-response :refer :all]
            [taoensso.timbre :as log]))

(defn- notify-users [access-token bot-access-token]
  (let [members (slack/list-members access-token)]
    (doseq [{:keys [id first-name]} members]
      ; don't overrun the slack servers
      (Thread/sleep 500)

      (slack/send-message bot-access-token id
                    (format "Hello, %s." first-name)))))

(defroutes oauth-routes
  (context "/oauth" []
    (GET "/" []
      :query-params [code :- String]
      :summary "Give Slack our authorization code so we can be helpful!"
      (if (slack/auth-slack code notify-users)
        (ok "Application authorized!")
        (unauthorized)))))