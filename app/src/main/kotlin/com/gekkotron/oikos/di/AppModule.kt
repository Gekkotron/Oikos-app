package com.gekkotron.oikos.di

import android.content.Context
import android.util.Log
import com.gekkotron.oikos.connection.DirectConnectionTester
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.connection.SharedSubscriptions
import com.gekkotron.oikos.connection.TransportFactory
import com.gekkotron.oikos.connection.clientIdFor
import com.gekkotron.oikos.connection.networkChanges
import com.gekkotron.oikos.connection.setupClientIdFor
import com.gekkotron.oikos.connection.toSpec
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.flows.FlowsRepository
import com.gekkotron.oikos.feature.home.presentation.DefaultDispatcher
import com.gekkotron.oikos.feature.onboarding.ConnectionTester
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.ActiveTransport
import com.gekkotron.oikos.transport.AthenaGatewayApi
import com.gekkotron.oikos.transport.MqttTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun transportFactory(
        @ApplicationContext context: Context,
        http: OkHttpClient,
        @ApplicationScope scope: CoroutineScope,
    ): TransportFactory = TransportFactory(scope, context.networkChanges(), http)

    @Provides
    @Singleton
    fun activeTransport(
        profiles: ProfileRepository,
        factory: TransportFactory,
        @ApplicationScope scope: CoroutineScope,
    ): ActiveTransport = ActiveTransport(
        // installId() is a suspend DataStore write; resolved once rather than re-run on every emission.
        specs = flow {
            val id = profiles.installId()
            emitAll(combine(profiles.state, profiles.route) { state, route -> state.toSpec(clientIdFor(id), route) })
        },
        factory = factory::create,
        scope = scope,
    )

    @Provides
    @Singleton
    fun sharedSubscriptions(active: ActiveTransport): SharedSubscriptions = SharedSubscriptions(active)

    @Provides
    fun transport(shared: SharedSubscriptions): MqttTransport = shared

    @Provides
    @Singleton
    fun entitySourceSession(
        transport: MqttTransport,
        cache: EntityCache,
        flows: FlowsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): EntitySourceSession =
        EntitySourceSession(transport, scope, diagnostics = { Log.w("Oikos", "${it.source}: ${it.message}") }, cache = cache, flowsSink = flows)

    /** Unscoped: each new Home gets the source for the current profile (spec §2). Home only exists while Ready. */
    @Provides
    fun entitySource(session: EntitySourceSession, profiles: ProfileRepository): EntitySource =
        session.current((profiles.state.value as? ProfileState.Ready)?.profile)

    @Provides
    @Singleton
    fun connectionTester(profiles: ProfileRepository, http: OkHttpClient): ConnectionTester =
        DirectConnectionTester(clientId = { setupClientIdFor(profiles.installId()) }, gatewayApi = { AthenaGatewayApi(it, http) })
}
