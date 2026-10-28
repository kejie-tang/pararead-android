package org.pararead.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration

private val statusResources = mapOf(
    "准备就绪" to R.string.ready,
    "已取消" to R.string.cancelled,
    "模型已就绪，思考模式已关闭" to R.string.model_ready,
    "模型已删除" to R.string.model_deleted,
    "已切换模型" to R.string.model_switched,
    "正在准备下载…" to R.string.preparing_download,
    "等待下载…" to R.string.waiting_download,
    "等待网络或系统调度…" to R.string.waiting_network,
    "网络中断，等待自动续传…" to R.string.resuming_network,
    "正在校验模型…" to R.string.verifying_model,
    "正在导入 GGUF…" to R.string.importing_gguf,
    "正在取消…" to R.string.cancelling,
    "正在切换模型…" to R.string.switching_model,
    "正在删除模型…" to R.string.deleting_model,
    "正在暂停阅读器并释放模型…" to R.string.pausing_reader,
    "测速已取消" to R.string.speed_cancelled,
    "正在加载本机模型…" to R.string.loading_model,
    "正在释放模型…" to R.string.releasing_model,
    "测速完成，结果已保存在本机" to R.string.speed_saved,
    "测速完成；本机保存失败，请导出 JSON" to R.string.speed_unsaved,
    "测速已取消；未覆盖上次完整结果" to R.string.speed_cancel_keep,
    "正在取消并释放模型…" to R.string.cancelling_model,
    "导入已取消" to R.string.import_cancelled,
    "术语表已导入" to R.string.glossary_imported,
    "术语表已导出" to R.string.glossary_exported,
    "测速结果已导出" to R.string.speed_exported,
    "速度测试期间阅读器已暂停" to R.string.reader_paused,
    "模型 SHA-256 校验失败，请重试下载" to R.string.bad_sha,
    "模型不是 GGUF 文件" to R.string.bad_gguf,
    "模型文件不完整，请重试下载" to R.string.incomplete_model,
    "请求失败，请重试" to R.string.request_failed,
    "模型文件不存在，请重新下载" to R.string.model_missing,
    "上次 GPU 会话未正常结束，已恢复 CPU；可手动重新启用 Vulkan。" to R.string.gpu_recovered,
    "（自动）" to R.string.automatic_suffix,
)
/** Display only: transport state and existing persisted reports retain their original values. */
@Composable internal fun displayStatus(message: String): String {
    val context = LocalContext.current
    statusResources[message]?.let { return context.getString(it) }
    val configuration = LocalConfiguration.current
    if (configuration.locales[0].language == "zh") return message
    return when {
        message.startsWith("正在下载 ") -> "Downloading " + message.removePrefix("正在下载 ")
        message.startsWith("正在导入 ") -> "Importing " + message.removePrefix("正在导入 ")
        message.startsWith("测速失败：") -> "Test failed: " + message.removePrefix("测速失败：")
        message.startsWith("已回退 CPU：") -> "Using CPU fallback: " + message.removePrefix("已回退 CPU：")
        message.startsWith("实际后端：") -> "Actual backend: " + message.removePrefix("实际后端：")
        message.startsWith("请求后端：") -> "Requested backend: " + message.removePrefix("请求后端：").replace("Vulkan GPU（实验）", "Vulkan GPU (experimental)").replace("加载后确认", "confirmed after loading")
        message.startsWith("首次加载 · 第 ") -> "Fresh model · run " + message.removePrefix("首次加载 · 第 ").removeSuffix(" 次")
        message.startsWith("模型已加载 · 第 ") -> "Warm model · run " + message.removePrefix("模型已加载 · 第 ").removeSuffix(" 次")
        message.startsWith("纯引擎 pp512 / tg128 · 第 ") -> "Raw engine pp512 / tg128 · run " + message.removePrefix("纯引擎 pp512 / tg128 · 第 ").removeSuffix(" 次")
        else -> message // Preserve model/driver/server diagnostics and actual document content.
    }
}
