# Offline question normalization for Rive lookup

Mr Green can expand a small set of reviewed English/Hinglish question forms before looking up an **existing** literal Rive answer. This is a conservative first step, not general conversation intelligence, automatic language detection, unrestricted spelling correction or a translation model.

Examples supported by the code-owned tables:

| Typed question | Lookup candidate |
| --- | --- |
| `wht is engien?` | `what is engine` |
| `engien kya h?` | `what is engine` |
| `memroy kya hai` | `what is memory` |
| `wht is ur name` | `what is your name` |
| `aapka naam kya hai` | `what is your name` |
| `who r u` | `who are you` |

An answer is returned only when a unique existing rule matches. No rules or answers are shipped or created by this feature. An original existing match has priority; existing alias collisions still fail closed. A new candidate uses literal lookup, never a second fuzzy correction. The UI identifies a normalized rule match without displaying or saving the rewritten question as the user's message.

## Privacy and boundaries

`RuleQuestionNormalizer` and `RuleQuestionLookup` are used only inside `RiveBrain.reply`. Actions still receive the original input before rule lookup. Original chat persistence, memory retrieval, identity lookup, model prompts and learning inputs remain on their existing paths; the normalizer does not replace their text. The controller's pre-existing outer whitespace trimming is unchanged.

Lookup does not change `.rive` bytes, exports, saved triggers, rule counts or revisions. Existing separately consented auto-learning behavior is unchanged; the preprocessor itself never calls learning or writes storage. Nothing is sent to a network service.

## Deliberate limits

Question forms are anchored to the entire input. Definition templates accept only the closed, reviewed subject list in `RuleQuestionNormalizer.kt`; unfamiliar words, names, numbers, negated clauses and commands are not guessed. Ambiguous abbreviations such as `m`, `k` or `h` are not globally expanded. `h` is accepted only as the final word of an approved Hinglish definition question. Meaningful punctuation, URLs and unsupported apostrophes retain the existing matcher's rejection behavior. Unsupported text falls through to the existing local-only route; without a usable local model and a matching rule it cannot invent an answer.

## Libraries and licensing

This implementation uses Kotlin/JVM standard-library functionality and the application's **existing** RiveScript dependency. It adds no runtime library, model, dataset, native binary, network call or ABI requirement. The build's existing `armeabi-v7a`-only filter and Android 11/API 30 target remain unchanged.

[HinglishNorm](https://github.com/piyushmakhija5/hinglishNorm) is a Hindi-English normalization dataset, with a [research paper reporting baseline results](https://aclanthology.org/2020.coling-industry.13/), not a drop-in Android normalizer verified for this device. Its repository licenses the work under CC BY-NC-SA 4.0. No HinglishNorm data, code or model is copied or bundled here; the small tables/templates are original project code. If that material is added later, include appropriate attribution, license and change notices, and meet applicable noncommercial/share-alike obligations even for personal use in a public repository.

## Tests

`RuleQuestionNormalizerTest` covers approved forms, intentional refusals, existing-rule requirements, collisions, original-rule priority and unchanged learning equivalence. Existing `QuestionMatcherTest` covers the earlier surface/typo policy. `RiveBrainTest.normalizedLookupIsReadOnlyAndRequiresAnExistingAnswer` checks interpreter integration, restart, unchanged disk bytes, exports, revision and rule counts. `scripts/check-conversation-wiring.py` guards the original-input controller boundary and offline-only routing.

On an Android development setup, run:

```sh
./gradlew :app:testDebugUnitTest --tests 'im.mrgreen.rive.*Test'
python3 scripts/check-conversation-wiring.py
```

Pure Kotlin/JVM tests are host tests, and Robolectric uses an emulated Android API. Neither proves Redmi 9 performance or behavior. No physical Redmi testing or APK release is claimed by this change.

Implementation verification: 20 pure JVM matcher/normalizer tests, 28 real RiveScript/RiveBrain tests under Robolectric API 30, and 11 Python controller source-contract guards passed. Kotlin 2.1.20 and the project's existing dependency versions were used in a temporary standalone host harness because this environment has no Android SDK. That harness did not run the full Android Gradle build, compile the full controller, test controller database persistence at runtime, or produce an APK. The controller's original-message path is guarded by source checks; the actual rule-file read-only behavior is exercised by RiveBrain tests.
