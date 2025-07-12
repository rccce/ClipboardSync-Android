package com.siw.clipboardsync.di

import com.siw.clipboardsync.data.repository.AuthRepository
import com.siw.clipboardsync.data.repository.ClipboardRepository
import com.siw.clipboardsync.data.repository.DeviceRepository
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    // All repositories are provided via constructor injection
    // No additional bindings needed since they're concrete classes
}