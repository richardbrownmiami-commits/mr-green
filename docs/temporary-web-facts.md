# Temporary web answers without a language model

Mr Green can now retrieve public source material for an **explicit** question and answer on the phone without a GGUF or cloud AI. Ordinary chat, registered actions, local memory and Rive behavior remain unchanged. This is a bounded source-reading and shallow-reasoning feature, not general natural-language intelligence.

## Try it

Open **Overview → All capabilities → Knowledge** and enable **Allow explicitly requested web/MCP access**. Access starts off each app process; enabling it does not start automatic searches.

In Chat or Knowledge, enter:

```text
ask web What is photosynthesis?
ask web Why do plants need sunlight?
ask web https://example.org/article | What does this article say about rain?
```

Replace the example URL with a real public HTTPS page. Questions are limited to 300 characters; URLs to 2,048. Ordinary questions without `ask web` do not search. Malformed commands show help rather than falling through to local-model generation.

Search first uses DuckDuckGo's public HTML results, then fetches up to two source pages. If search is blocked or no pages can be retrieved, it tries Wikipedia article summaries. Search snippets are not evidence. Encyclopedia coverage is narrower than general web search and is not reliable for every current question. Explicit page URLs are not silently replaced with a different source.

## What answers mean

When the retrieved evidence fits a strict small English grammar, the actual [OpenNARS-for-Applications](https://github.com/opennars/OpenNARS-for-Applications) C core checks a direct claim or one-step deduction. For example, a source containing `Robin is a bird.` and `All birds are animals.` can support `Is Robin an animal?` The response identifies both premises and their source links. Simple articles and regular plural category labels are supported; multiword entities, arbitrary grammar, irregular morphology and broad commonsense reasoning are not.

Only affirmative declarative premises are translated. Questions, unsupported compound sentences, hedges and relevant contrary claims do not become affirmative logical answers. This is intentionally cautious, not comprehensive contradiction detection. The native engine must produce the exact requested relation before a logical answer is shown. All inputs and inference cycles are capped. Native ONA is a small symbolic core, not another language model; the upstream Python/NLTK bridge is not bundled.

Otherwise the app quotes a few relevant source sentences with citations, or says no relevant answer was found. Extractive quotations are **not** a claim that the app has proved an explanation. Pages can be false or inconsistent; even a valid deduction is only as reliable as its premises. Website content is data, never executable instructions or authority to take phone actions.

## What leaves the phone and what is saved

Only the explicitly typed search question goes to DuckDuckGo and, on fallback, Wikipedia. Page mode sends a request to the chosen site. These services see ordinary network metadata such as IP address, and the question itself may be sensitive: type public search terms only. No chat history, recalled memory, notes, screen contents or cloud-model prompt is attached.

Retrieved pages and the new web question/answer are not written to SQL chat, conversation memory, Rive learning, a knowledge index or files. Raw source text is request-local; native reasoning state is reset after each request. The displayed exchange is a single in-memory UI result. Tap **Clear temporary web answer** to remove it. It also clears on the next submitted message, after five minutes, on memory trim or service stop, and on process death. It is not available for later chat recall. Existing independently requested document imports, saved notes and older local chat are not deleted or changed.

The public-web transport retains HTTPS verification, actual socket-DNS public-address checks, response/time limits, and blocks redirects, proxies, cookies and automatic retries. Some large pages, redirected links, dynamic sites and search challenges will not work. Stop prevents further source fetching and answer publication; an already running HTTP request may take up to its existing 12-second timeout to return. No continuous background browsing is added.

## Build and validation

The feature stays in the same `im.mrgreen` app with `targetSdk=30` and only `armeabi-v7a` native packaging. ONA is vendored at a pinned MIT-licensed revision with documented embedded capacity/privacy patches in `vendor/ona/UPSTREAM.txt`. Its measured host static memory is approximately 1.2 MiB; this is not an Android peak-RAM measurement.

Host checks include parser, retrieval/fallback/citation/cancellation tests, temporary dispatch/UI tests, pure English-premise planning tests, source privacy guards and the actual native ONA harness (`bash scripts/test-ona-host.sh`). Native host inference is not proof of Android JNI execution or target-phone performance.

The preview passed `testDebugUnitTest`, `assembleDebug` and `lintDebug`: 289 tests in 52 suites, zero failures/errors and three skipped tests. ARMv7-only APK inspection found the ONA library alongside the existing native libraries. The Android ONA library has 832,320 bytes of static BSS; this is not total process or peak RAM. Source privacy guards, existing SQL-memory/storage checks and safe-note-search checks also passed. The native harness asserts actual direct/deduced answers, unknown results, state reset, cycle bounds and no source-text stdout output. Public DuckDuckGo testing returned a challenge, while Wikipedia search and article-summary endpoints returned usable responses, motivating the explicit encyclopedia fallback.

A build must still be installed and tested on the Redmi 9 with the matching signing identity. Do not uninstall or clear data to overcome a signature mismatch. Test web-off behavior, ordinary offline chat, a page question, blocked-search fallback, unsupported and contrary claims, Stop, Clear, expiry and absence of saved web turns. No Redmi installation, MIUI survival, latency or phone RAM result is claimed by host tests.
