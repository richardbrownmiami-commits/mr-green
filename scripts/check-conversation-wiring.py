#!/usr/bin/env python3
"""Host source-contract guards; production Android routing is also unit-tested.
These checks do not certify device inference, permissions, or network isolation.
"""
from pathlib import Path
import os
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(os.environ.get('MR_GREEN_CONTROLLER', ROOT / 'app/src/main/java/im/mrgreen/core/AgentController.kt')).read_text()
CHAT = SOURCE.split('private suspend fun replyWithRulesOrModel', 1)[1].split('/** Explicit builder-only', 1)[0]
EPHEMERAL = SOURCE.split('suspend fun answerContext', 1)[1].split('fun cloudDisabled', 1)[0]

class ConversationWiring(unittest.TestCase):
    def test_local_actions_precede_chat_and_model(self):
        submit = SOURCE.split('fun submit(', 1)[1].split('private suspend fun replyWithRulesOrModel', 1)[0]
        self.assertIn('deterministicFirst<Unit>', submit)
        self.assertLess(submit.index('app.roadmap.execute'), submit.index('replyWithRulesOrModel(text)'))

    def test_normalization_is_only_inside_rule_lookup(self):
        submit = SOURCE.split('fun submit(', 1)[1].split('private suspend fun replyWithRulesOrModel', 1)[0]
        self.assertIn('app.roadmap.execute(text,', submit)
        self.assertIn('app.rive.reply(text)', CHAT)
        self.assertIn('app.rive.learn(text,', CHAT)
        rule_reply = CHAT.split('if (ruleReply != null', 1)[1].split('// A personalized answer', 1)[0]
        self.assertIn('app.store.addMessage("user", text)', rule_reply)
        for forbidden in ('RuleQuestionNormalizer', 'RuleQuestionLookup', 'candidate.question'):
            self.assertNotIn(forbidden, SOURCE)
        brain = (ROOT / 'app/src/main/java/im/mrgreen/rive/RiveBrain.kt').read_text()
        reply = brain.split('@Synchronized fun reply(', 1)[1].split('/** Caller must gate', 1)[0]
        self.assertIn('current.matcher.match(question)', reply)
        self.assertIn('current.engine.reply("owner", match.trigger)', reply)
        self.assertNotIn('commit(', reply)
        self.assertNotIn('learn(', reply)
        self.assertIn('RuleQuestionLookup(effective.keys)', brain)
        self.assertIn('val owner = learned + imported', brain)
        self.assertIn('bundled.filterKeys { !ownerMatcher.hasEquivalent(it) } + owner', brain)
        commit = brain.split('private fun commit(', 1)[1].split('private fun compile(', 1)[0]
        self.assertNotIn('serialize(bundled)', commit)

    def test_retrieval_precedes_saved_replies(self):
        self.assertLess(CHAT.index('conversationMemory.retrieve'), CHAT.index('app.rive.reply'))
        self.assertIn('if (needsMemory) null else app.rive.reply', CHAT)
        self.assertIn('includeRecent = MemoryChatPolicy.needsRecent(text)', CHAT)

    def test_no_cloud_generation_in_any_controller_path(self):
        for forbidden in ('gateway.tryChat', 'GatewayRequest(', 'tryCloud(', 'mode = "Cloud"'):
            self.assertNotIn(forbidden, SOURCE)
        gateway = (ROOT / 'app/src/main/java/im/mrgreen/gateway/GatewayClient.kt').read_text()
        # Network-capable legacy implementation must be blocked by code-owned policy,
        # not by mutable saved preferences, connectivity, privacy class, or cache hits.
        client = gateway.split('class GatewayClient', 1)[1]
        self.assertIn('lastReason = "LOCAL_ONLY"', client)
        self.assertIn('internal fun isCloudAvailable(): Boolean = false', client)
        self.assertNotIn('transport.post(', client)
        self.assertNotIn('transport.get(', client)

    def test_identity_data_precedes_saved_replies_and_uses_bounded_prompt(self):
        self.assertLess(CHAT.index('app.identity.directReply(text)'), CHAT.index('app.rive.reply'))
        self.assertIn('ConversationPrompt.fitIdentity(app.identity.corePrompt()', CHAT)
        self.assertNotIn('qa_examples', CHAT)

    def test_fitted_prompt_is_the_generated_prompt(self):
        self.assertIn('val turns = fitted.turns', CHAT)
        self.assertIn('engine.generate(turns,', CHAT)
        self.assertNotIn('store.messages()', CHAT)
        self.assertIn('full_history=false', CHAT)

    def test_partial_answers_are_not_persisted_or_auto_learned(self):
        self.assertIn('if (result.ok && !cancelled.get() && buffer.isNotBlank()) app.store.addMessage', CHAT)
        self.assertIn('val learnThisTurn = !needsMemory &&', CHAT)

    def test_screen_context_remains_ephemeral(self):
        self.assertNotIn('addMessage', EPHEMERAL)
        self.assertNotIn('conversationMemory', EPHEMERAL)
        self.assertNotIn('tryChat', EPHEMERAL)

    def test_no_generic_remote_planner_injection(self):
        learning = (ROOT / 'app/src/main/java/im/mrgreen/learning/LearningRuntime.kt').read_text()
        self.assertNotIn('PlannerGateway', learning)
        self.assertNotIn('plannerGateway', learning)
        self.assertIn('reusable(request)?.toString()', learning)

    def test_external_ai_recipes_are_denied_before_accessibility(self):
        recipe = (ROOT / 'app/src/main/java/im/mrgreen/recipes/RecipeRuntime.kt').read_text()
        self.assertLess(recipe.index('External AI conversation recipes are disabled'), recipe.index('val context = service'))
        self.assertNotIn('ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt', recipe)

    def test_public_tools_are_explicit_and_separately_gated(self):
        knowledge = (ROOT / 'app/src/main/java/im/mrgreen/knowledge/KnowledgeRuntime.kt').read_text()
        self.assertIn('lower.startsWith("search web ")', knowledge)
        self.assertIn('require(!localOnly)', knowledge)
        self.assertIn('require(reviewedPublicArguments)', knowledge)
        self.assertIn('require(userApprovedRemoteAction)', knowledge)

if __name__ == '__main__':
    unittest.main(verbosity=2)
