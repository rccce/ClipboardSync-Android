package com.siw.clipboardsync.di

import android.content.Context
import com.siw.clipboardsync.manager.ServiceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {
    
    @Provides
    @Singleton
    fun provideServiceManager(
        @ApplicationContext context: Context
    ): ServiceManager {
        return ServiceManager(context)
    }
}