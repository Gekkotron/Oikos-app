package com.gekkotron.oikos.di

import android.util.Log
import com.gekkotron.oikos.alerts.media.MediaRoutes
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.effectiveHistoryUrl
import com.gekkotron.oikos.data.isLikelyLocalHost
import com.gekkotron.oikos.flows.FlowsRepository
import com.gekkotron.oikos.flows.HttpFlowsClient
import com.gekkotron.oikos.transport.LanOrGatewayGet
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FlowsModule {
    /**
     * The hub's flows: toggles go to the same host as OikosHistory (the profile's history URL), over the LAN or
     * the gateway. The demo and no profile have no hub, so the client fails without a request.
     */
    @Provides
    @Singleton
    fun flowsRepository(profiles: ProfileRepository, routes: MediaRoutes, http: OkHttpClient, @ApplicationScope scope: CoroutineScope): FlowsRepository {
        val lanOrGateway = LanOrGatewayGet(routes, http, ::isLikelyLocalHost, log = { Log.w("OikosFlows", it) })
        val client = HttpFlowsClient(
            baseUrl = {
                val ready = profiles.state.first { it !is ProfileState.Loading } as? ProfileState.Ready
                (ready?.profile as? ConnectionProfile.Direct)?.effectiveHistoryUrl()
            },
            post = { url, json -> lanOrGateway.post(url, json) },
        )
        return FlowsRepository(client, scope)
    }
}
