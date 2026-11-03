package dev.phonebot.android

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag

internal fun providerIconResource(id: String): Int? = when (id) {
    "amazon-bedrock" -> R.drawable.ic_provider_amazon_bedrock
    "ant-ling" -> R.drawable.ic_provider_bailing
    "anthropic" -> R.drawable.ic_provider_anthropic
    "azure" -> R.drawable.ic_provider_azure
    "baseten" -> R.drawable.ic_provider_baseten
    "cerebras" -> R.drawable.ic_provider_cerebras
    "cloudflare-ai-gateway" -> R.drawable.ic_provider_cloudflare_ai_gateway
    "cloudflare-workers-ai" -> R.drawable.ic_provider_cloudflare_workers_ai
    "deepseek" -> R.drawable.ic_provider_deepseek
    "fireworks" -> R.drawable.ic_provider_fireworks_ai
    "github-copilot" -> R.drawable.ic_provider_github_copilot
    "google" -> R.drawable.ic_provider_google
    "google-vertex" -> R.drawable.ic_provider_google_vertex
    "groq" -> R.drawable.ic_provider_groq
    "huggingface" -> R.drawable.ic_provider_huggingface
    "kimi-coding" -> R.drawable.ic_provider_kimi_code_plan_global
    "meta" -> R.drawable.ic_provider_meta
    "minimax", "minimax-cn" -> R.drawable.ic_provider_minimax
    "mistral" -> R.drawable.ic_provider_mistral
    "moonshotai", "moonshotai-cn" -> R.drawable.ic_provider_moonshotai
    "nvidia" -> R.drawable.ic_provider_nvidia
    "openai", "openai-codex" -> R.drawable.ic_provider_openai
    "opencode", "opencode-go" -> R.drawable.ic_provider_opencode
    "openrouter" -> R.drawable.ic_provider_openrouter
    "qwen-token-plan", "qwen-token-plan-cn", "qwen-token-plan-individual" -> R.drawable.ic_provider_alibaba
    "together" -> R.drawable.ic_provider_togetherai
    "vercel-ai-gateway" -> R.drawable.ic_provider_vercel
    "xai" -> R.drawable.ic_provider_xai
    "xiaomi", "xiaomi-token-plan-ams", "xiaomi-token-plan-cn", "xiaomi-token-plan-sgp" -> R.drawable.ic_provider_xiaomi
    "zai", "zai-coding-cn" -> R.drawable.ic_provider_zai
    else -> null
}

@Composable internal fun ProviderIcon(id: String, modifier: Modifier = Modifier) {
    val resource = providerIconResource(id)
    val iconModifier = modifier.size(PhoneUi.icon).testTag("provider-icon-$id")
    if (resource == null) Icon(painterResource(R.drawable.ic_ui_user_round), null, iconModifier)
    else Icon(painterResource(resource), null, iconModifier)
}
