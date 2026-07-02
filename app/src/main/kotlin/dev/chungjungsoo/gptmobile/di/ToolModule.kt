package dev.chungjungsoo.gptmobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.tool.AndroidDeviceLocationReader
import dev.chungjungsoo.gptmobile.data.tool.AndroidToolPermissionChecker
import dev.chungjungsoo.gptmobile.data.tool.BuiltInTools
import dev.chungjungsoo.gptmobile.data.tool.JsonToolCallParser
import dev.chungjungsoo.gptmobile.data.tool.ToolExecutor
import dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestrator
import dev.chungjungsoo.gptmobile.data.tool.ToolPermissionChecker
import dev.chungjungsoo.gptmobile.data.tool.ToolProvider
import dev.chungjungsoo.gptmobile.data.tool.ToolRegistry
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {
    @Provides
    @Singleton
    fun provideToolProviders(
        webSearchRepository: WebSearchRepository,
        webPageExtractor: WebPageExtractor,
        @ApplicationContext context: Context
    ): List<ToolProvider> = BuiltInTools(
        webSearchRepository,
        webPageExtractor,
        AndroidDeviceLocationReader(context)
    ).providers()

    @Provides
    @Singleton
    fun provideToolRegistry(toolProviders: List<@JvmSuppressWildcards ToolProvider>): ToolRegistry =
        ToolRegistry(toolProviders)

    @Provides
    @Singleton
    fun provideToolPermissionChecker(
        @ApplicationContext context: Context
    ): ToolPermissionChecker = AndroidToolPermissionChecker(context)

    @Provides
    @Singleton
    fun provideToolExecutor(
        toolRegistry: ToolRegistry,
        toolPermissionChecker: ToolPermissionChecker
    ): ToolExecutor = ToolExecutor(
        toolRegistry = toolRegistry,
        permissionChecker = toolPermissionChecker
    )

    @Provides
    @Singleton
    fun provideJsonToolCallParser(): JsonToolCallParser = JsonToolCallParser()

    @Provides
    @Singleton
    fun provideToolLoopOrchestrator(
        toolExecutor: ToolExecutor,
        jsonToolCallParser: JsonToolCallParser
    ): ToolLoopOrchestrator = ToolLoopOrchestrator(
        toolExecutor = toolExecutor,
        jsonToolCallParser = jsonToolCallParser
    )
}
