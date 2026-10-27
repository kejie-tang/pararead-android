package org.pararead.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*

@RunWith(AndroidJUnit4::class)
class ModelLibraryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun saved(asset: ModelAsset) = SavedModel(InstalledModel("/unused/${asset.sha256}.gguf", asset.sha256, asset.bytes), asset.name, asset.license)
    @Test fun bothRecommendedDownloadsAreDirectAndSmallModelIsPinned() {
        var selected: ModelAsset? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { MaterialTheme {
                ModelLibraryDialog(ModelState(), onDownload = { selected = it }, onUse = {}, onDelete = {},
                    onRetry = {}, onCancel = {}, onImport = {}, onMore = {}, onClose = {})
            } } }
            compose.onNodeWithText("Qwen3-0.6B").assertExists()
            compose.onNodeWithText("Q4_K_M · 397 MB").assertExists()
            compose.onNodeWithText("Qwen3-1.7B").performScrollTo().assertExists()
            compose.onAllNodesWithText("下载并使用").onFirst().performScrollTo().performClick()
            assertEquals(RecommendedModels.fast, selected)
        }
    }
    @Test fun savedModelsCanSwitchAndDeletionRequiresConfirmation() {
        val fast = saved(RecommendedModels.fast); val large = saved(DefaultModel.asset)
        var used: String? = null; var deleted: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { MaterialTheme {
                ModelLibraryDialog(ModelState(installed = fast.model, models = listOf(fast, large)),
                    onDownload = {}, onUse = { used = it }, onDelete = { deleted = it },
                    onRetry = {}, onCancel = {}, onImport = {}, onMore = {}, onClose = {})
            } } }
            compose.onNodeWithText("当前使用：Qwen3-0.6B").assertExists()
            compose.onNodeWithText("当前使用", substring = false).assertIsNotEnabled()
            compose.onNodeWithText("使用", substring = false).performScrollTo().performClick()
            assertEquals(large.model.sha256, used)
            compose.onAllNodesWithText("删除").onFirst().performScrollTo().performClick()
            compose.onNodeWithText("删除 Qwen3-0.6B？").assertExists()
            assertNull(deleted)
            compose.onNodeWithText("取消", substring = false).performClick()
            assertNull(deleted)
            compose.onAllNodesWithText("删除").onFirst().performScrollTo().performClick()
            compose.onNodeWithText("确认删除").performClick()
            assertEquals(fast.model.sha256, deleted)
        }
    }
    @Test fun failedDownloadShowsTheSelectedModelAndSpecificRetry() {
        var retried = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { MaterialTheme {
                ModelLibraryDialog(ModelState(phase = ModelPhase.ERROR, target = RecommendedModels.fast,
                    message = "下载失败", canRetry = true), onDownload = {}, onUse = {}, onDelete = {},
                    onRetry = { retried = true }, onCancel = {}, onImport = {}, onMore = {}, onClose = {})
            } } }
            compose.onNodeWithText("Qwen3-0.6B · 397 MB").assertExists()
            compose.onNodeWithText("重试此模型下载").performClick()
            assertTrue(retried)
        }
    }
}
