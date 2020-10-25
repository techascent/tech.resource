(defproject techascent/tech.resource "5.02-SNAPSHOT"
  :description "Exception-safe threadsafe resource management"
  :url "http://github.com/tech-ascent/tech.resource"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [org.clojure/tools.logging "1.1.0"]]
  :profiles {:codox
             {:dependencies [[codox-theme-rdash "0.1.2"]]
              :plugins [[lein-codox "0.10.7"]]
              :codox {:project {:name "tech.resource"}
                      :metadata {:doc/format :markdown}
                      :themes [:rdash]
                      :source-paths ["src"]
                      :output-path "docs"
                      :doc-paths ["topics"]
                      :source-uri "https://github.com/techascent/tech.resource/blob/master/{filepath}#L{line}"
                      :namespaces [tech.v3.resource]}}}
  :java-source-paths ["java"]
  :aliases {"codox" ["with-profile" "codox,dev" "codox"]})
