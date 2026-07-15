package cn.nabr.chatwithchat.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import cn.nabr.chatwithchat.data.tool.AndroidDeviceLocationReader
import cn.nabr.chatwithchat.data.tool.AndroidToolPermissionChecker
import cn.nabr.chatwithchat.data.tool.BuiltInTools
import cn.nabr.chatwithchat.data.tool.JsonToolCallParser
import cn.nabr.chatwithchat.data.tool.NoOpToolAuditSink
import cn.nabr.chatwithchat.data.tool.ToolApprovalAuthority
import cn.nabr.chatwithchat.data.tool.ToolAuditSink
import cn.nabr.chatwithchat.data.tool.ToolExecutionContextFactory
import cn.nabr.chatwithchat.data.tool.ToolExecutor
import cn.nabr.chatwithchat.data.tool.ToolLoopOrchestrator
import cn.nabr.chatwithchat.data.tool.ToolPermissionChecker
import cn.nabr.chatwithchat.data.tool.ToolProvider
import cn.nabr.chatwithchat.data.tool.ToolRegistry
import cn.nabr.chatwithchat.data.websearch.WebPageExtractor
import cn.nabr.chatwithchat.data.websearch.WebSearchRepository
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
    fun provideToolApprovalAuthority(): ToolApprovalAuthority = ToolApprovalAuthority()

    @Provides
    @Singleton
    fun provideToolExecutionContextFactory(): ToolExecutionContextFactory = ToolExecutionContextFactory()

    @Provides
    @Singleton
    fun provideToolAuditSink(): ToolAuditSink = NoOpToolAuditSink

    @Provides
    @Singleton
    fun provideToolExecutor(
        toolRegistry: ToolRegistry,
        toolPermissionChecker: ToolPermissionChecker,
        toolApprovalAuthority: ToolApprovalAuthority,
        toolExecutionContextFactory: ToolExecutionContextFactory,
        toolAuditSink: ToolAuditSink
    ): ToolExecutor = ToolExecutor(
        toolRegistry = toolRegistry,
        permissionChecker = toolPermissionChecker,
        approvalAuthority = toolApprovalAuthority,
        executionContextFactory = toolExecutionContextFactory,
        auditSink = toolAuditSink
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
