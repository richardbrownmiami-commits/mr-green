#!/usr/bin/env python3
"""Rebuild public, code-owned defaults only; never reads saved user data."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
rules = {}

def add(keys, answer):
    for key in keys.split('|'):
        assert key not in rules, key
        rules[key] = answer

add('hi|hello|hey|hi mr green|hello mr green|hey mr green|hi green|hello green', 'Hello! I’m Mr Green. How can I help?')
add('good morning|good afternoon|good evening', 'Hello! What can I help you with?')
add('how are you|how are you doing|how r u|how are you mr green|how do you do|how is it going', 'I’m here to help locally. I’m software, so I don’t have feelings. Ask for battery level or system status for a live phone reading.')
add('thanks|thank you|thanks mr green|thank you mr green|thanks a lot|thank you very much|many thanks|thx|ty', 'You’re welcome!')
add('bye|goodbye|good night|see you|see you later|talk to you later', 'Goodbye! Come back whenever you need help.')
add('are you offline|do you need internet|do you use cloud|does mr green work offline', 'Chat stays on this phone. Greetings, identity help and supported local actions need no GGUF. Other generated answers need a loaded local GGUF; there is no cloud model fallback.')
add('do i need a model|do you need a model|what is gguf', 'GGUF is the local language-model file used for generated answers. Greetings, identity help, battery, time and supported device actions work without it.')
add('how do i check status|status help', 'Ask ‘battery level’, ‘what time is it’, ‘storage status’, ‘system status’ or ‘model status’. Ask ‘job status’ for the last recorded registered-action result in this app session.')
# Identity copies are portable defaults only. Live app routing always checks the user's profile first.
profile = json.loads((ROOT / 'app/src/main/assets/identity/profile.json').read_text())
identity = {qa['question'].rstrip('?').lower(): qa['answer'] for qa in profile['stable_qa']}
for key, answer in identity.items():
    add(key, answer)
add('identity|your identity|who is mr green|what is mr green|tell me about mr green|what is your name|what s your name|your name', identity['who are you'])
add('what can you do|help|mr green help', identity['what can you help with'])
add('are you a human|are you conscious', identity['are you alive'])
# Small, curated greeting pairs adapted from ChatterBot Corpus's BSD-3-Clause greetings.yml.
add('greetings', 'Hello')
add('nice to meet you|hi nice to meet you|it is a pleasure to meet you', 'Thank you. You too.')
add('what s up|what is up', 'Not much, how about you?')
# Original acknowledgement replies. These are conversation, never approvals or success evidence.
add('that s good|that is good|thats good|good|great|that s great|that is great|nice|awesome|excellent|wonderful|perfect', 'Good to hear! What would you like help with next?')
add('sure|okay|ok|alright|all right|sounds good|yes|yep|yeah|absolutely|of course', 'Okay. Tell me what you’d like to do next. This reply does not approve or run an action.')
add('no|nope|no thanks|no thank you|not now|maybe later', 'No problem. I’ll wait for your next request.')
add('sorry|i am sorry|my apologies', 'No problem. How can I help?')
add('you are welcome|you re welcome|welcome', 'Thanks! What would you like help with?')
add('well done|good job|you are helpful|that helped|that was helpful', 'Thanks for the feedback. What would you like help with next?')
add('please|can you help me|help me', 'Tell me what you need help with. You can ask about local notes, battery, time, or system status.')
header = '''! version = 2.0

// Mr Green built-in static conversation defaults. No live readings or action completion claims.
// Greeting adaptations: ChatterBot Corpus, Gunther Cox (2017), BSD-3-Clause.
// Source: https://github.com/gunthercox/chatterbot-corpus/blob/eec45b284424c9784a5baab78368b1a9ff3b656f/chatterbot_corpus/data/english/greetings.yml
// License: assets/licenses/chatterbot-corpus-BSD-3-Clause.txt (also docs/licenses in source).
// Other app-help and acknowledgement replies are Mr Green code-owned text.
// Identity entries are default copies; editable identity wins in the app.

'''
license_text = (ROOT / 'docs/licenses/chatterbot-corpus-BSD-3-Clause.txt').read_text(encoding='utf-8')
notice = ''.join(f'// {line}\n' if line else '//\n' for line in license_text.splitlines())
text = header + notice + '\n' + ''.join(f'+ {key}\n- {answer}\n\n' for key, answer in rules.items())
(ROOT / 'app/src/main/assets/rive/builtin-conversation.rive').write_text(text, encoding='utf-8')
print(f'Generated {len(rules)} literal conversation rules')
