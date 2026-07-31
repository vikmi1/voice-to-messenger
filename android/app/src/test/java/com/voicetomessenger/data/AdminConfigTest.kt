package com.voicetomessenger.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Конфиг бэкенда в том виде, в каком его отдаёт `/admin/api/config`. */
private const val SERVER_JSON = """
{
  "active_provider": "mistral",
  "api_token": "app-token",
  "admin_token": "admin-token",
  "providers": {
    "mistral": {"label": "Mistral", "kind": "openai", "base_url": "https://api.mistral.ai/v1",
                "api_key": "mk", "model": "mistral-small-latest", "asr_model": "voxtral-mini-latest"},
    "anthropic": {"label": "Anthropic (Claude)", "kind": "anthropic",
                  "base_url": "https://api.anthropic.com/v1", "api_key": "ak", "model": "claude-sonnet-5",
                  "asr_model": ""}
  },
  "asr": {"provider": "mistral"},
  "base_prompt": "Ты — редактор коротких сообщений.",
  "styles": [{"id": "neutral", "name": "Нейтрально", "prompt": "спокойный тон."}],
  "languages": [{"code": "off", "name": "Без перевода"}, {"code": "de", "name": "Немецкий"}],
  "future_setting": {"nested": 42}
}
"""

class AdminConfigTest {

    @Test
    fun `парсит конфиг бэкенда`() {
        val cfg = AdminConfig.parse(SERVER_JSON)

        assertEquals("mistral", cfg.activeProvider)
        assertEquals(2, cfg.providers.size)
        assertEquals(ProviderKind.ANTHROPIC, cfg.provider("anthropic")!!.kind)
        assertEquals(ProviderKind.OPENAI, cfg.provider("mistral")!!.kind)
        assertEquals("claude-sonnet-5", cfg.provider("anthropic")!!.model)
        // Модель распознавания у каждого провайдера своя, а не одна на весь конфиг.
        assertEquals("voxtral-mini-latest", cfg.provider("mistral")!!.asrModel)
        assertEquals("mistral", cfg.asrProvider)
        assertEquals("admin-token", cfg.adminToken)
        assertEquals(1, cfg.styles.size)
        assertEquals(2, cfg.languages.size)
    }

    @Test
    fun `провайдер без kind считается OpenAI-совместимым`() {
        val cfg = AdminConfig.parse("""{"providers": {"groq": {"base_url": "x", "model": "y"}}}""")
        assertEquals(ProviderKind.OPENAI, cfg.provider("groq")!!.kind)
    }

    @Test
    fun `смена модели не теряет остальные настройки`() {
        val cfg = AdminConfig.parse(SERVER_JSON)
        val edited = cfg
            .withProvider(cfg.provider("anthropic")!!.copy(model = "claude-opus-5"))
            .copy(activeProvider = "anthropic")

        val out = JSONObject(edited.toJson())

        assertEquals("anthropic", out.getString("active_provider"))
        assertEquals(
            "claude-opus-5",
            out.getJSONObject("providers").getJSONObject("anthropic").getString("model"),
        )
        // Ключи и промпты не должны пострадать от правки одной только модели.
        assertEquals("ak", out.getJSONObject("providers").getJSONObject("anthropic").getString("api_key"))
        assertEquals("mk", out.getJSONObject("providers").getJSONObject("mistral").getString("api_key"))
        assertEquals("Ты — редактор коротких сообщений.", out.getString("base_prompt"))
        assertEquals("app-token", out.getString("api_token"))
        assertEquals(
            "voxtral-mini-latest",
            out.getJSONObject("providers").getJSONObject("mistral").getString("asr_model"),
        )
        assertEquals("neutral", out.getJSONArray("styles").getJSONObject(0).getString("id"))
        assertEquals("спокойный тон.", out.getJSONArray("styles").getJSONObject(0).getString("prompt"))
    }

    @Test
    fun `незнакомые приложению настройки переживают сохранение`() {
        val out = JSONObject(AdminConfig.parse(SERVER_JSON).toJson())
        assertEquals(42, out.getJSONObject("future_setting").getInt("nested"))
    }

    @Test
    fun `пустые стили и языки отбрасываются при сохранении`() {
        val cfg = AdminConfig.parse(SERVER_JSON)
        val edited = cfg.copy(
            styles = cfg.styles + StyleConfig("", "", ""),
            languages = cfg.languages + LanguageConfig("", "новый"),
        )

        val out = JSONObject(edited.toJson())
        assertEquals(1, out.getJSONArray("styles").length())
        assertEquals(2, out.getJSONArray("languages").length())
    }

    @Test
    fun `новый провайдер попадает в JSON`() {
        val cfg = AdminConfig.parse(SERVER_JSON).copy(
            providers = AdminConfig.parse(SERVER_JSON).providers + ProviderConfig(
                key = "groq", label = "Groq", kind = ProviderKind.OPENAI,
                baseUrl = "https://api.groq.com/openai/v1", apiKey = "gk", model = "llama-3.3-70b",
                asrModel = "",
            ),
        )

        val provs = JSONObject(cfg.toJson()).getJSONObject("providers")
        assertTrue(provs.has("groq"))
        assertEquals("openai", provs.getJSONObject("groq").getString("kind"))
        assertEquals("llama-3.3-70b", provs.getJSONObject("groq").getString("model"))
    }

    @Test
    fun `удалённый провайдер исчезает из JSON`() {
        val cfg = AdminConfig.parse(SERVER_JSON)
        val edited = cfg.copy(providers = cfg.providers.filterNot { it.key == "anthropic" })

        val provs = JSONObject(edited.toJson()).getJSONObject("providers")
        assertEquals(1, provs.length())
        assertTrue(provs.has("mistral"))
    }
}
