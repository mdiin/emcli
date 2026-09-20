# On guiding LLM's
- emcli_query '... | where id in {...}' syntax elaboration (currently it sometimes puts `where id=in {...}`, which does not fail for some reason)
- Better guidance that it is *not possible* to add an element and place it in a slice in one command. LLM's often do this:

{
  "args": "--name \"Registration Form\" --element-type \"screen\" --slice 15",
  "verb": "add"
}

- emcli should reject extra parameters; this will provide guidance for the llm regsrding the previous point

# Operation improvements
- Slice reordering by `--before` and `--after` instead of by index (I think wireframing does this as well)
  - This makes it much simpler to reorder slices for both humans and llm's
  - Should go for slice insertion as well, i.e. "add slice to timeline before/after slice 42"


# Error while llm created automation
Because an llm attempted to add an "automation trigger" (no such thing...).

The LLM wrote this about the error:

> ⚠️ Important Note on Automation Configuration: The system reported a server processing error while attempting to configure the automation's trigger condition (i.e., linking it to the User registered event). The automation element exists, but its automated trigger from the User registered event is currently not active.

Tool reported error:

Stderr:
----- Error --------------------------------------------------------------------
Type:     com.fasterxml.jackson.core.JsonParseException
Message:  Unrecognized token 'java': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
 at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1]
Location: /home/mvi/.gitlibs/libs/io.github.mdiin/emcli/14a0d1cfbcf1580cbecbea3fe4393c1126c99903/src/emcli/cli.clj:50:47

----- Context ------------------------------------------------------------------
46:     (catch Exception e
47:       (die (str "Could not reach emcli server at " url
48:                 "\n  Is `emcli serve` running? (" (ex-message e) ")")))))
49:
50: (defn- parse-body [resp] (some-> (:body resp) (json/parse-string true)))
                                                  ^--- Unrecognized token 'java': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
 at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 1]
51:
52:
53:
54: ;; --- subcommands -----------------------------------------------------------
55:

----- Stack trace --------------------------------------------------------------
cheshire.parse/parse                - <built-in>
cheshire.core/parse-string          - <built-in>
babashka.impl.cheshire/parse-string - <built-in>
cheshire.core/parse-string          - cheshire/core.clj:198:1
emcli.cli/parse-body                - /home/mvi/.gitlibs/libs/io.github.mdiin/emcli/14a0d1cfbcf1580cbecbea3fe4393c1126c99903/src/emcli/cli.clj:50:47
... (run with --debug to see elided elements)
emcli.cli/do-authoring              - /home/mvi/.gitlibs/libs/io.github.mdiin/emcli/14a0d1cfbcf1580cbecbea3fe4393c1126c99903/src/emcli/cli.clj:96:17
emcli.cli/do-authoring              - /home/mvi/.gitlibs/libs/io.github.mdiin/emcli/14a0d1cfbcf1580cbecbea3fe4393c1126c99903/src/emcli/cli.clj:92:1
emcli.cli                           - /home/mvi/.gitlibs/libs/io.github.mdiin/emcli/14a0d1cfbcf1580cbecbea3fe4393c1126c99903/src/emcli/cli.clj:544:17
clojure.core/apply                  - clojure/core.clj:662:1
user                                - NO_SOURCE_PATH:1:34

Error in emcli console:

Sat Sep 19 21:51:14 CEST 2026 [] ERROR - POST /authoring/add-field-origin
java.lang.ClassCastException: java.lang.Integer cannot be cast to clojure.lang.Named
        at clojure.core$name.invokeStatic(core.clj:1614)
        at clojure.core$name.invoke(core.clj:1608)
        at sci.lang.Var.invoke(lang.cljc:217)
        at sci.impl.analyzer$return_call$reify__5177.eval(analyzer.cljc:1756)
        at sci.impl.analyzer$return_call$reify__5251.eval(analyzer.cljc:1756)
        at sci.impl.analyzer$return_call$reify__5179.eval(analyzer.cljc:1756)
        at sci.impl.analyzer$return_if$reify__4931.eval(analyzer.cljc:947)
        at sci.impl.fns$fun$arity_1__1465.invoke(fns.cljc:130)
        at sci.lang.Var.invoke(lang.cljc:217)
        at sci.impl.analyzer$return_call$reify__5179.eval(analyzer.cljc:1756)
        at sci.impl.analyzer$return_call$reify__5263.eval(analyzer.cljc:1756)
        at sci.impl.analyzer$return_call$reify__5251.eval(analyzer.cljc:1756)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:957)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:957)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$analyze_let_STAR_$reify__4899.eval(analyzer.cljc:717)
        at sci.impl.fns$fun$arity_3__1476.invoke(fns.cljc:132)
        at sci.lang.Var.invoke(lang.cljc:221)
        at sci.impl.analyzer$return_call$reify__5251.eval(analyzer.cljc:1756)
        at sci.impl.analyzer$analyze_let_STAR_$reify__4903.eval(analyzer.cljc:741)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:957)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$return_if$reify__4933.eval(analyzer.cljc:958)
        at sci.impl.analyzer$analyze_let_STAR_$reify__4907.eval(analyzer.cljc:785)
        at sci.impl.fns$fun$arity_2__1470.invoke(fns.cljc:131)
        at sci.lang.Var.invoke(lang.cljc:219)
        at sci.impl.analyzer$return_call$reify__5213.eval(analyzer.cljc:1756)
        at sci.impl.fns$fun$arity_1__1465.invoke(fns.cljc:130)
        at org.httpkit.server$wrap_ring_websocket$ring_handler_STAR___16715.invoke(server.clj:443)
        at org.httpkit.server.HttpHandler.runSync(RingHandler.java:144)
        at org.httpkit.server.HttpHandler.run(RingHandler.java:138)
        at java.base@25/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:545)
        at java.base@25/java.util.concurrent.FutureTask.run(FutureTask.java:328)
        at java.base@25/java.util.concurrent.ThreadPerTaskExecutor$ThreadBoundFuture.run(ThreadPerTaskExecutor.java:323)
        at java.base@25/java.lang.Thread.runWith(Thread.java:1487)
        at java.base@25/java.lang.VirtualThread.run(VirtualThread.java:456)
        at java.base@25/java.lang.VirtualThread$VThreadContinuation$1.run(VirtualThread.java:248)
