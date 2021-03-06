(ns posthere.integration.request
  "Test request handling by the POSThere.io service."
  (:require [clojure.string :as s]
            [midje.sweet :refer :all]
            [ring.util.codec :refer (form-encode)]
            [ring.mock.request :refer (request body content-type header content-length)]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [posthere.util.uuid :refer (uuid)]
            [posthere.app :refer (app)]
            [posthere.examples :as examples]
            [posthere.capture-request :as capture :refer (post-response-body)]
            [posthere.pretty-print :as pretty-print :refer (pretty-print-xml-and-declaration)]
            [posthere.storage :refer (requests-for)]
            [cheshire.core :refer (parse-string generate-string)]
            [clojure.data.xml :refer (parse-str indent-str)]))

(def string-body "I'm a little teapot.")

(def json-body "{\"lyric\": \"I'm a little teapot.\"}")
(def pretty-json (generate-string (parse-string json-body) {:pretty true}))

(def xml-declaration "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
(def xml-body "<song><lyric>I'm a little teapot.</lyric></song>")
(def xml-body-with-declaration (str xml-declaration "<song><lyric>I'm a little teapot.</lyric></song>"))

(def pretty-xml (pretty-print-xml-and-declaration xml-body))
(def pretty-xml-with-declaration (str xml-declaration "\n" (pretty-print-xml-and-declaration xml-body)))

(def params {
  "password" "This1sMySup3rS3cr3tPassw0rdAndY0uCanN0tGuess1t"
  "most-evolved" "ferrets"
  "super-bowl" "Buccaneers"
})

(def header-values {
  "user-agent" "ring-mock"
  "accept" "inevitability/death"
  "content-type" "delicious/cake"
  "content-length" "42"
  "super-bowl" "Buccaneers"
})

(def bad-status-codes [103 209 309 435 452 512 "foo"])

(defn- url-for [url-uuid]
  (str "/" url-uuid))

(facts "about responses"

  (doseq [method [:post :put :patch]]

    (fact "default response status is provided"
      (let [url-uuid (uuid)
            url (url-for url-uuid)
            request (request method url)
            response (app request)]
        (:status response) => capture/default-http-status-code))

    (fact "default response status is provided for a URL with multiple segments"
      (let [url-uuid "foo/bar/blat/bloo"
            url (url-for url-uuid)
            request (request method url)
            response (app request)]
        (:status response) => capture/default-http-status-code))

    (fact "response content-type is text/plain"
      (let [url-uuid (uuid)
            url (url-for url-uuid)
            request (request method url)
            response (app request)]
        (get-in response [:headers "Content-Type"]) => "text/plain"))

    (fact "response body is text informing the developer what to do next"
      (let [url-uuid (uuid)
            url (url-for url-uuid)
            request (request method url)
            response (app request)]
        (:body response) => (post-response-body url-uuid "localhost")))

    (fact "requesting to the example URL is not allowed"
      (let [url examples/example-url
            request (request method url)
            response (app request)]
        (:status response) => examples/post-to-example-status
        (:body response) => examples/post-to-example-body))))

(facts "about responses getting saved"

  (doseq [method [:post :put :patch]]

    (fact "timestamp is saved, and it's about now"
      (let [url-uuid (uuid)
            url (url-for url-uuid)
            request (request method url)
            response (app request)
            timestamp (f/parse (:timestamp (first (requests-for url-uuid))))]
          (t/after? timestamp (t/minus (t/now) (t/seconds 10))) => true ; after 10 secs ago
          (t/before? timestamp (t/now)) => true)) ; before now

    (fact "headers are saved"
      (let [url-uuid (uuid)
            url (url-for url-uuid)
            request (request method url)
            ; wrap the request in repeated calls to ring mock's header/3 function for each
            ; header name/value pair
            headers (reduce #(header %1 %2 (get header-values %2)) request (keys header-values))
            response (app headers)
            request-headers (:headers (first (requests-for url-uuid)))]
        ; verify storage contains each name/value header in the :headers map
        (doseq [header-name (keys header-values)]
          request-headers => (contains {header-name (get header-values header-name)}))))

    (facts "parsed-query-string is saved"

      (fact "without a body"
        (let [url-uuid (uuid)
              query-string (form-encode params)
              url (url-for (str url-uuid "?" query-string))
              request (request method url)
              response (app request)]
          (:parsed-query-string (first (requests-for url-uuid))) => params))

      (fact "with a body"
        (let [url-uuid (uuid)
              query-string (form-encode params)
              url (url-for (str url-uuid "?" query-string))
              request (request method url)
              body (body request string-body)
              response (app body)]
          (:parsed-query-string (first (requests-for url-uuid))) => params)))))

(facts "about responses getting saved"

  (doseq [method [:post :put :patch]]

    (facts "the body is saved with an appropriate derived content type"

      (fact "when the body is a string"
        (let [url-uuid (uuid)
              url (url-for url-uuid)
              request (request method url)
              body (body request string-body)
              response (app body)
              stored-request (first (requests-for url-uuid))]
          (:derived-content-type stored-request) => nil
          (get-in stored-request [:headers "content-type"]) => nil
          (:body stored-request) => string-body
          (not (:body-overflow stored-request)) => true
          (not (:invalid-body stored-request)) => true))

      (facts "when the body is URL encoded"

        (fact "as parsed when the content-type indicaties it's URL encoded"
          (let [url-uuid (uuid)
                url (url-for url-uuid)
                request (request method url)
                header (header request :content-type pretty-print/form-urlencoded)
                body (body header params)
                response (app body)
                stored-request (first (requests-for url-uuid))]
            (:derived-content-type stored-request) => pretty-print/url-encoded
            (get-in stored-request [:headers "content-type"]) => pretty-print/form-urlencoded
            (:body stored-request) => params
            (not (:body-overflow stored-request)) => true
            (not (:invalid-body stored-request)) => true))

        (fact "as parsed when they don't tell us the content-type"
          (let [url-uuid (uuid)
                url (url-for url-uuid)
                request (request method url)
                body (body request params)
                response (app body)
                stored-request (first (requests-for url-uuid))]
            (:derived-content-type stored-request) => pretty-print/url-encoded
            (get-in stored-request [:headers "content-type"]) => pretty-print/form-urlencoded
            (:body stored-request) => params
            (not (:body-overflow stored-request)) => true
            (not (:invalid-body stored-request)) => true))

        (fact "as a string when the content-type says it's URL encoded but it's not"
          (let [url-uuid (uuid)
                url (url-for url-uuid)
                request (request method url)
                header (header request :content-type pretty-print/form-urlencoded)
                body (body header string-body)
                response (app body)
                stored-request (first (requests-for url-uuid))]
            (:derived-content-type stored-request) => nil
            (get-in stored-request [:headers "content-type"]) => pretty-print/form-urlencoded
            (:body stored-request) => string-body
            (not (:body-overflow stored-request)) => true
            (:invalid-body stored-request) => true)))


      (facts "when the body is JSON"

        (fact "as pretty-printed when the content-type indicates it's JSON"
          (doseq [mime-type pretty-print/json-mime-types]
            (let [url-uuid (uuid)
                  url (url-for url-uuid)
                  request (request method url)
                  header (header request :content-type mime-type)
                  body (body header json-body)
                  response (app body)
                  stored-request (first (requests-for url-uuid))]
              (:derived-content-type stored-request) => pretty-print/json-encoded
              (get-in stored-request [:headers "content-type"]) => mime-type
              (:body stored-request) => pretty-json
              (not (:body-overflow stored-request)) => true
              (not (:invalid-body stored-request)) => true)))

        (fact "as pretty-printed when they don't tell us the content-type"
          (let [url-uuid (uuid)
                url (url-for url-uuid)
                request (request method url)
                body (body request json-body)
                response (app body)
                stored-request (first (requests-for url-uuid))]
            (:derived-content-type stored-request) => pretty-print/json-encoded
            (get-in stored-request [:headers "content-type"]) => nil
            (:body stored-request) => pretty-json
            (not (:body-overflow stored-request)) => true
            (not (:invalid-body stored-request)) => true))

        (fact "as a string when the content-type says it's JSON but it's not"
          (doseq [mime-type pretty-print/json-mime-types]
            (let [url-uuid (uuid)
                  url (url-for url-uuid)
                  request (request method url)
                  header (header request :content-type mime-type)
                  body (body header xml-body)
                  response (app body)
                  stored-request (first (requests-for url-uuid))]
              (:derived-content-type stored-request) => nil
              (get-in stored-request [:headers "content-type"]) => mime-type
              (:body stored-request) => xml-body
              (not (:body-overflow stored-request)) => true
              (:invalid-body stored-request) => true))))

      (facts "when the body is XML"

        (fact "as pretty-printed when the content-type indicates it's XML"
          (doseq [mime-type pretty-print/xml-mime-types]
            (let [url-uuid (uuid)
                  url (url-for url-uuid)
                  request (request method url)
                  header (header request :content-type mime-type)
                  body (body header xml-body)
                  response (app body)
                  stored-request (first (requests-for url-uuid))]
              (:derived-content-type stored-request) => pretty-print/xml-encoded
              (get-in stored-request [:headers "content-type"]) => mime-type
              (:body stored-request) => pretty-xml
              (not (:body-overflow stored-request)) => true
              (not (:invalid-body stored-request)) => true)
            (let [url-uuid (uuid)
                  url (url-for url-uuid)
                  request (request method url)
                  header (header request :content-type mime-type)
                  body (body header xml-body-with-declaration)
                  response (app body)
                  stored-request (first (requests-for url-uuid))]
              (:derived-content-type stored-request) => pretty-print/xml-encoded
              (get-in stored-request [:headers "content-type"]) => mime-type
              (:body stored-request) => pretty-xml-with-declaration
              (not (:body-overflow stored-request)) => true
              (not (:invalid-body stored-request)) => true)))

        (fact "as pretty-printed when they don't tell us the content-type"
          (let [url-uuid (uuid)
                url (url-for url-uuid)
                request (request method url)
                body (body request xml-body)
                response (app body)
                stored-request (first (requests-for url-uuid))]
            (:derived-content-type stored-request) => pretty-print/xml-encoded
            (get-in stored-request [:headers "content-type"]) => nil
            (:body stored-request) => pretty-xml
            (not (:body-overflow stored-request)) => true
            (not (:invalid-body stored-request)) => true))

        (fact "as a string when the content-type says it's XML but it's not"
          (doseq [mime-type pretty-print/xml-mime-types]
            (let [url-uuid (uuid)
                  url (url-for url-uuid)
                  request (request method url)
                  header (header request :content-type mime-type)
                  body (body header json-body)
                  response (app body)
                  stored-request (first (requests-for url-uuid))]
              (:derived-content-type stored-request) => nil
              (get-in stored-request [:headers "content-type"]) => mime-type
              (:body stored-request) => json-body
              (not (:body-overflow stored-request)) => true
              (:invalid-body stored-request) => true)))))))

;; TODO can we get mock ring request to not provide a content-length? It seems to NPE
;; if we dissoc it
(facts "about giant requests getting partially saved"

  (doseq [method [:post :put :patch]]

    (facts "doesn't save the body if content-length header > max body size"
      (let [url-uuid (uuid)
            url (url-for url-uuid)
            request (request method url)
            type-header (header request :content-type (first pretty-print/xml-mime-types))
            body (body type-header xml-body)
            length-header (content-length body (+ capture/max-body-size 1))
            response (app length-header)
            stored-request (first (requests-for url-uuid))]
        (:derived-content-type stored-request) => capture/too-big
        (get-in stored-request [:headers "content-type"]) => (first pretty-print/xml-mime-types)
        (:body stored-request) => nil
        (:body-overflow stored-request) => true
        (not (:invalid-body stored-request)) => false))

    (facts "doesn't save the body if there is a lying content-length header, and the  content is > 1MB"
      (let [url-uuid (uuid)
            url (url-for url-uuid)
            request (request method url)
            type-header (header request :content-type (first pretty-print/xml-mime-types))
            body (body type-header (apply str (take (+ capture/max-body-size 1) (repeat "x"))))
            length-header (content-length body (- capture/max-body-size 1))
            response (app length-header)
            stored-request (first (requests-for url-uuid))]
        (:derived-content-type stored-request) => capture/too-big
        (get-in stored-request [:headers "content-type"]) => (first pretty-print/xml-mime-types)
        (:body stored-request) => nil
        (:body-overflow stored-request) => true
        (not (:invalid-body stored-request)) => false))))

(facts "about requested status getting used as the status"

  (doseq [method [:post :put :patch]]

    (facts "valid HTTP statuses get used as the status"
      (doseq [status capture/http-status-codes]
        (let [url-uuid (uuid)
              url (url-for (str url-uuid "?foo=bar&status=" status))
              request (request method url)
              body (body request json-body)
              response (app body)
              stored-request (first (requests-for url-uuid))
              body (:body stored-request)
              parsed-body (:parsed-body stored-request)]
          (:status response) => status ; responded with the requested status
          (:status stored-request) => status ; stored the requested status
          ;; Removed status from the other query string params
          (doseq [value [body parsed-body]]
            (or (s/blank? value) (not (.contains value (str status)))) => true))))

    (facts "invalid HTTP statuses don't get used as the status"
      (doseq [status bad-status-codes]
        (let [url-uuid (uuid)
              url (url-for (str url-uuid "?foo=bar&status=" status))
              request (request method url)
              body (body request json-body)
              response (app body)
              stored-request (first (requests-for url-uuid))]
          (:status response) => capture/default-http-status-code ; responded with the default status
          (:status stored-request) => capture/default-http-status-code))))) ; stored the default status

(facts "GET, DELETE, and FOO requests don't get saved"
  (doseq [method [:get :delete :foo]]
    (let [url-uuid (uuid)
          url (url-for url-uuid)
          request (request method url)
          response (app request)]
      (empty? (requests-for url-uuid)) => true)))