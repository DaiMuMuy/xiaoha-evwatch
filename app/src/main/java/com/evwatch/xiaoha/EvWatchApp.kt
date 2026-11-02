package com.evwatch.xiaoha

import android.app.Application
import com.evwatch.xiaoha.data.network.HbApi
import com.evwatch.xiaoha.data.repository.ConfigRepository
import com.evwatch.xiaoha.ui.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

class EvWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@EvWatchApp)
            modules(appModule)
        }
    }
}

val appModule = module {
    single { ConfigRepository(androidContext()) }
    single { HbApi() }
    viewModel { MainViewModel(androidContext(), get(), get()) }
}
