package com.siw.clipboardsync.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.siw.clipboardsync.websocket.WebSocketClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebSocketModule {
    
    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }
    
    @Provides
    @Singleton
    fun provideWebSocketClient(gson: Gson): WebSocketClient {
        return WebSocketClient(gson)
    }
}