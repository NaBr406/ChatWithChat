package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryModelResolverTest {
    @Test
    fun `fixed preference resolves its exact platform and model pair`() = runBlocking {
        val first = platform(uid = "first", model = "shared-model")
        val second = platform(uid = "second", model = "second-current")
        val resolver = resolver(
            platforms = listOf(first, second),
            models = listOf(model(first.uid, "shared-model"), model(second.uid, "shared-model"))
        )

        val resolution = resolver.resolvePreference(MemoryModelPreference.Fixed(second.uid, "shared-model"))

        assertEquals(second.copy(model = "shared-model"), resolution.resolvedPlatform())
    }

    @Test
    fun `invalid stored preference fails closed without auto fallback`() = runBlocking {
        val eligible = platform(uid = "eligible")
        val resolver = resolver(listOf(eligible), listOf(model(eligible.uid, eligible.model)))
        val invalid = MemoryModelPreference.Invalid(
            platformUid = eligible.uid,
            modelId = null,
            reason = MemoryModelPreferenceInvalidReason.MISSING_MODEL_ID
        )

        val resolution = resolver.resolvePreference(invalid)

        assertEquals(
            MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.INVALID_PREFERENCE),
            resolution
        )
    }

    @Test
    fun `auto skips disabled uncredentialed and unusable provider configurations`() = runBlocking {
        val disabled = platform(uid = "disabled", enabled = false)
        val uncredentialed = platform(uid = "uncredentialed", token = " ")
        val unusableProvider = platform(uid = "unusable-provider", apiUrl = "")
        val eligible = platform(uid = "eligible", model = "eligible-current")
        val laterEligible = platform(uid = "later", model = "later-current")
        val resolver = resolver(
            platforms = listOf(disabled, uncredentialed, unusableProvider, eligible, laterEligible),
            models = listOf(
                model(disabled.uid, disabled.model),
                model(uncredentialed.uid, uncredentialed.model),
                model(unusableProvider.uid, unusableProvider.model),
                model(eligible.uid, eligible.model),
                model(laterEligible.uid, laterEligible.model)
            )
        )

        val resolution = resolver.resolveAuto()

        assertEquals(eligible, resolution.resolvedPlatform())
    }

    @Test
    fun `auto uses platform current model instead of first displayed catalog model`() = runBlocking {
        val platform = platform(uid = "platform", model = "z-current-model")
        val resolver = resolver(
            platforms = listOf(platform),
            models = listOf(
                model(platform.uid, modelId = "a-first-displayed", displayName = "A model"),
                model(platform.uid, modelId = platform.model, displayName = "Z model")
            )
        )

        val resolution = resolver.resolveAuto()

        assertEquals(platform.model, resolution.resolvedPlatform().model)
    }

    @Test
    fun `auto does not borrow duplicate enabled model id from another platform`() = runBlocking {
        val first = platform(uid = "first", model = "shared-model")
        val second = platform(uid = "second", model = "shared-model")
        val resolver = resolver(
            platforms = listOf(first, second),
            models = listOf(
                model(first.uid, first.model, enabled = false),
                model(second.uid, second.model, enabled = true)
            )
        )

        val resolution = resolver.resolveAuto()

        assertEquals(second.uid, resolution.resolvedPlatform().uid)
    }

    @Test
    fun `frozen duplicate model id resolves the exact platform pair`() = runBlocking {
        val first = platform(uid = "first", model = "first-current")
        val second = platform(uid = "second", model = "second-current")
        val resolver = resolver(
            platforms = listOf(first, second),
            models = listOf(
                model(first.uid, modelId = "shared-model"),
                model(second.uid, modelId = "shared-model")
            )
        )

        val resolution = resolver.resolveFrozen(platformUid = second.uid, modelId = "shared-model")

        assertEquals(second.copy(model = "shared-model"), resolution.resolvedPlatform())
    }

    @Test
    fun `frozen unavailable target never falls back to another eligible platform`() = runBlocking {
        val disabledTarget = platform(uid = "target", model = "target-current")
        val fallback = platform(uid = "fallback", model = "fixed-model")
        val disabledResolver = resolver(
            platforms = listOf(disabledTarget, fallback),
            models = listOf(
                model(disabledTarget.uid, modelId = "fixed-model", enabled = false),
                model(fallback.uid, fallback.model)
            )
        )

        val disabled = disabledResolver.resolveFrozen(disabledTarget.uid, "fixed-model")
        val missing = disabledResolver.resolveFrozen("missing-platform", "fixed-model")
        val disabledPlatform = resolver(
            platforms = listOf(disabledTarget.copy(enabled = false), fallback),
            models = listOf(
                model(disabledTarget.uid, modelId = "fixed-model"),
                model(fallback.uid, fallback.model)
            )
        ).resolveFrozen(disabledTarget.uid, "fixed-model")
        val uncredentialed = resolver(
            platforms = listOf(disabledTarget.copy(token = ""), fallback),
            models = listOf(
                model(disabledTarget.uid, modelId = "fixed-model"),
                model(fallback.uid, fallback.model)
            )
        ).resolveFrozen(disabledTarget.uid, "fixed-model")

        listOf(disabled, missing, disabledPlatform, uncredentialed).forEach { resolution ->
            assertEquals(
                MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.FROZEN_MODEL_UNAVAILABLE),
                resolution
            )
        }
    }

    @Test
    fun `frozen blank identity fails closed before repository fallback`() = runBlocking {
        val fallback = platform(uid = "fallback", model = "fallback-current")
        val resolver = resolver(
            platforms = listOf(fallback),
            models = listOf(model(fallback.uid, fallback.model))
        )

        val blankPlatform = resolver.resolveFrozen(platformUid = " ", modelId = "fixed-model")
        val blankModel = resolver.resolveFrozen(platformUid = fallback.uid, modelId = " ")

        listOf(blankPlatform, blankModel).forEach { resolution ->
            assertEquals(
                MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.INVALID_FROZEN_IDENTITY),
                resolution
            )
        }
    }

    private fun resolver(
        platforms: List<PlatformV2>,
        models: List<PlatformModelV2>
    ): MemoryModelResolver = MemoryModelResolver(settingRepository(platforms, models))

    private fun settingRepository(
        platforms: List<PlatformV2>,
        models: List<PlatformModelV2>
    ): SettingRepository {
        val handler = java.lang.reflect.InvocationHandler { _, method, arguments ->
            when (method.name) {
                "fetchPlatformV2s" -> platforms
                "fetchPlatformModels" -> if (method.parameterCount == 1) {
                    models
                } else {
                    models.filter { model -> model.platformUid == arguments?.firstOrNull() }
                }
                else -> error("Unexpected SettingRepository call: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            SettingRepository::class.java.classLoader,
            arrayOf(SettingRepository::class.java),
            handler
        ) as SettingRepository
    }

    private fun platform(
        uid: String,
        model: String = "current-model",
        enabled: Boolean = true,
        apiUrl: String = "https://example.test/v1",
        token: String? = "token",
        compatibleType: ClientType = ClientType.OPENAI
    ): PlatformV2 = PlatformV2(
        uid = uid,
        name = uid,
        compatibleType = compatibleType,
        enabled = enabled,
        apiUrl = apiUrl,
        token = token,
        model = model
    )

    private fun model(
        platformUid: String,
        modelId: String,
        displayName: String = modelId,
        enabled: Boolean = true
    ): PlatformModelV2 = PlatformModelV2(
        platformUid = platformUid,
        modelId = modelId,
        displayName = displayName,
        enabled = enabled
    )

    private fun MemoryModelResolution.resolvedPlatform(): PlatformV2 {
        assertTrue(this is MemoryModelResolution.Resolved)
        return (this as MemoryModelResolution.Resolved).platform
    }
}
