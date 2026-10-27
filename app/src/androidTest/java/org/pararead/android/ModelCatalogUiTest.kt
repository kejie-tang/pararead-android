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
class ModelCatalogUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun selectedFileDisplaysLicenseSizeAndDownloadsThePinnedAsset() {
        val asset = ModelAsset("LiquidAI/test · model.gguf", "https://huggingface.co/LiquidAI/test/resolve/${"a".repeat(40)}/model.gguf", 229312224,
            "b".repeat(64), "lfm1.0", "LiquidAI/test", "a".repeat(40))
        var selected: ModelAsset? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                ModelCatalogDialog(ModelCatalogState(details = HfModelDetails(asset.repository, asset.revision, asset.license, listOf(HfModelFile("model.gguf", asset)))),
                    null, {}, {}, {}, {}, { selected = it }, {})
            } } }
            compose.onNodeWithText("模型许可：lfm1.0").assertExists()
            compose.onNodeWithText("218 MiB", substring = true).assertExists()
            compose.onNodeWithText("下载并使用此模型").performScrollTo().performClick()
            assertEquals(asset, selected)
        }
    }
    @Test fun settingsOpensSearchAndCloseReturnsToSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("离线 GGUF").performClick()
            compose.onAllNodesWithText("模型管理").onLast().performScrollTo().performClick()
            compose.onNodeWithText("更多模型（Hugging Face）").performScrollTo().performClick()
            compose.onNodeWithText("模型名称或 org/repo").assertExists()
            compose.onNodeWithText("搜索 GGUF").assertIsEnabled()
            compose.onAllNodesWithText("关闭").onLast().performClick()
            compose.onAllNodesWithText("关闭").onLast().performClick()
            compose.onNodeWithText("翻译设置").assertExists()
        }
    }
}
