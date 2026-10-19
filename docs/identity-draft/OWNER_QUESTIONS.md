# Choose how Mr Green should answer

The draft assumes calm, practical, short English replies and calls you “you”. These are placeholders, not final decisions. The helper must remain **local GGUF only**; cloud answering live chat is not an option to enable here.

## First decisions

1. **What kind of assistant should the APK feel like?** A practical phone helper, a knowledgeable companion, a task-focused agent, or a mixture? Which role comes first?
2. **What persona?** Calm/professional, friendly/conversational, or a light butler style? Should it be serious or occasionally humorous? How should it address you: no title, your chosen name, “sir”, or something else?
3. **Which reply language?** English, Hindi, Hinglish, or match your language each turn? For Hindi/Hinglish, Roman script, Devanagari, or match your input?
4. **How detailed should answers be?** One or two lines by default, a short answer plus steps, or detailed explanations? Should it explain only when asked “why”?
5. **Which first five jobs matter most?** Choose from notes, time/date/math, Clock setup, app/settings handoffs, local conversation/memory, notifications, media, imported documents, reviewed routines or Doctor. You can name other jobs, but they will be marked planned until supported. Screen reading on the target normal Android 11 build is blocked.
6. **How should progress appear?** Only start/final messages, important step changes, or a detailed timeline? After a failure, would you like a short reason and next step, with technical details only on request?

## Follow-up choices before implementation

7. **Learning and memory:** Should proposed new skills always appear in a review inbox? Should optional unverified saved model replies stay off? What preferences should it offer to remember, and should it ask before storing them? Permission, repair, installation and risky-action approvals stay mandatory.
8. **Voice:** Text first or spoken replies by default? Which language/voice style? Android offline voice packs/services are prerequisites; no new speech model is proposed, and offline preference alone is not proof the provider never uses network.
9. **Examples:** Please give three questions and three phone jobs you want to say naturally, plus an example of an answer you would like and one you dislike. These will guide phrasing, not expand current capabilities by themselves. Use invented names/content if the examples will be committed publicly.
10. **APK experience:** Chat-first with a visible status badge, or a dashboard-first layout? The draft contains no UI change. Would you like any later implementation to start only with local-only routing and the approved identity/answers, before broader automation?

Answering these chooses the draft's content. It does **not** authorize app wiring, a build, permission changes or a release. Cloud Doctor is unimplemented and deferred; it needs no decision to finish this identity draft.
