package io.legado.app.help.ai

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 漫画 / AI 漫剧 两个 System Workflow Package 的回归测试。
 *
 * 这两个包是「SKILL.md + modules/*.md」的包目录结构，子文件靠 use_skill 按需加载；
 * 一旦有人改坏 frontmatter、删掉模块文件或动了能力绑定，这里会第一时间失败。
 */
class AiComicDramaWorkflowPackageInstrumentedTest {

    @Test
    fun comicPackageExposesAllModulesAndFilesAreReadable() {
        val skill = AiSkillRegistry.managementSkills()
            .single { it.id == AiSkillRegistry.SKILL_AI_COMIC }
        val paths = AiSkillRegistry.skillFilePaths(skill.id)

        assertEquals(AiSkillRegistry.SKILL_AI_COMIC, skill.id)
        assertTrue(skill.builtIn)
        assertEquals("SKILL.md", paths.first())
        assertTrue(paths.size > 1)
        assertTrue("modules/scripting.md" in paths)
        assertTrue("modules/character.md" in paths)
        assertTrue("modules/prompt.md" in paths)
        assertTrue("modules/protocol.md" in paths)
        paths.forEach { path ->
            assertTrue(AiSkillRegistry.readSkillFile(skill.id, path).isNotBlank())
        }
    }

    @Test
    fun comicModePinsImageCapabilityAndChainedWorkflow() {
        val mode = AgentModeRegistry.aiComic
        val workflow = requireNotNull(AiSkillRegistry.systemWorkflow(AiSkillRegistry.SKILL_AI_COMIC))

        assertEquals("1", workflow.revision)
        assertEquals(AiSkillRegistry.SKILL_AI_COMIC, mode.systemWorkflowId)
        assertTrue(AiSkillRegistry.SKILL_AI_COMIC in mode.availableSystemSkillIds)
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.generate_image"))
        // 长链生成靠它回读分镜 local_path，防止垫图链因丢上下文而断
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.list_project"))
        // 成片：漫画可拼长图、可建命名项目
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.create_project"))
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.compose"))
        // 垫图链的两个硬约束：同一 project_id 串联、shot_index 递增
        assertTrue(workflow.prompt.contains("project_id"))
        assertTrue(workflow.prompt.contains("shot_index"))
        assertTrue(workflow.prompt.contains("垫图链"))

        val skillSet = requireNotNull(
            AiSkillPackageRegistry.systemPackageSet(mode.availableSystemSkillIds)
        )
        assertNotNull(
            AiAgentSkillTools.toolDefinition(
                activeSkillId = workflow.id,
                contentHash = skillSet.contentHash,
                availableSkillIds = mode.availableSystemSkillIds
            )
        )

        val scripting = loadSkill(
            name = AiSkillRegistry.SKILL_AI_COMIC,
            path = "modules/scripting.md",
            activeSkillId = workflow.id,
            skillSetHash = skillSet.contentHash,
            availableSkillIds = mode.availableSystemSkillIds
        )
        assertTrue(scripting.contains("# 分镜拆解（AI 漫画）"))

        val character = loadSkill(
            name = AiSkillRegistry.SKILL_AI_COMIC,
            path = "modules/character.md",
            activeSkillId = workflow.id,
            skillSetHash = skillSet.contentHash,
            availableSkillIds = mode.availableSystemSkillIds
        )
        assertTrue(character.contains("垫图链"))

        val protocol = loadSkill(
            name = AiSkillRegistry.SKILL_AI_COMIC,
            path = "modules/protocol.md",
            activeSkillId = workflow.id,
            skillSetHash = skillSet.contentHash,
            availableSkillIds = mode.availableSystemSkillIds
        )
        assertTrue(protocol.contains("ai_media_generate_image"))

        val prompt = loadSkill(
            name = AiSkillRegistry.SKILL_AI_COMIC,
            path = "modules/prompt.md",
            activeSkillId = workflow.id,
            skillSetHash = skillSet.contentHash,
            availableSkillIds = mode.availableSystemSkillIds
        )
        assertTrue(prompt.contains("# 提示词与画面规范（AI 漫画）"))
    }

    @Test
    fun dramaPackageExposesAllModulesAndFilesAreReadable() {
        val skill = AiSkillRegistry.managementSkills()
            .single { it.id == AiSkillRegistry.SKILL_AI_DRAMA }
        val paths = AiSkillRegistry.skillFilePaths(skill.id)

        assertEquals(AiSkillRegistry.SKILL_AI_DRAMA, skill.id)
        assertTrue(skill.builtIn)
        assertEquals("SKILL.md", paths.first())
        assertTrue(paths.size > 1)
        assertTrue("modules/scripting.md" in paths)
        assertTrue("modules/character.md" in paths)
        assertTrue("modules/prompt.md" in paths)
        assertTrue("modules/protocol.md" in paths)
        paths.forEach { path ->
            assertTrue(AiSkillRegistry.readSkillFile(skill.id, path).isNotBlank())
        }
    }

    @Test
    fun dramaModePinsBothCapabilitiesAndFirstLastFrameChain() {
        val mode = AgentModeRegistry.aiDrama
        val workflow = requireNotNull(AiSkillRegistry.systemWorkflow(AiSkillRegistry.SKILL_AI_DRAMA))

        assertEquals("1", workflow.revision)
        assertEquals(AiSkillRegistry.SKILL_AI_DRAMA, mode.systemWorkflowId)
        assertTrue(AiSkillRegistry.SKILL_AI_DRAMA in mode.availableSystemSkillIds)
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.generate_image"))
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.generate_video"))
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.list_project"))
        // 漫剧额外：命名项目 + 离线配音 + 成片拼装
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.create_project"))
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.generate_audio"))
        assertTrue(mode.fixedMcpCapabilityIds.contains("ai_media.compose"))
        // 漫剧靠首尾帧链保连续，主提示必须讲清楚
        assertTrue(workflow.prompt.contains("首尾帧"))
        assertTrue(workflow.prompt.contains("shot_index"))

        val skillSet = requireNotNull(
            AiSkillPackageRegistry.systemPackageSet(mode.availableSystemSkillIds)
        )
        assertNotNull(
            AiAgentSkillTools.toolDefinition(
                activeSkillId = workflow.id,
                contentHash = skillSet.contentHash,
                availableSkillIds = mode.availableSystemSkillIds
            )
        )

        val prompt = loadSkill(
            name = AiSkillRegistry.SKILL_AI_DRAMA,
            path = "modules/prompt.md",
            activeSkillId = workflow.id,
            skillSetHash = skillSet.contentHash,
            availableSkillIds = mode.availableSystemSkillIds
        )
        assertTrue(prompt.contains("# 提示词与视频规范（AI 漫剧）"))

        val protocol = loadSkill(
            name = AiSkillRegistry.SKILL_AI_DRAMA,
            path = "modules/protocol.md",
            activeSkillId = workflow.id,
            skillSetHash = skillSet.contentHash,
            availableSkillIds = mode.availableSystemSkillIds
        )
        assertTrue(protocol.contains("ai_media_generate_video"))
    }

    @Test
    fun bothCreativeModesAreListedInRegistry() {
        val ids = AgentModeRegistry.all().map { it.id }
        assertTrue(AgentModeRegistry.AI_COMIC_ID in ids)
        assertTrue(AgentModeRegistry.AI_DRAMA_ID in ids)
    }

    private fun loadSkill(
        name: String,
        path: String? = null,
        activeSkillId: String,
        skillSetHash: String,
        availableSkillIds: List<String>
    ): String {
        return requireNotNull(
            AiAgentSkillTools.call(
                name = AiAgentSkillTools.USE_SKILL,
                arguments = JsonObject().apply {
                    addProperty("name", name)
                    path?.let { addProperty("path", it) }
                },
                activeSkillId = activeSkillId,
                contentHash = skillSetHash,
                availableSkillIds = availableSkillIds
            )
        ).get("content").asString
    }
}
