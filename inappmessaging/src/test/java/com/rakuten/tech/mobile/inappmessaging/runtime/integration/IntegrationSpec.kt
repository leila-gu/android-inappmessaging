package com.rakuten.tech.mobile.inappmessaging.runtime.integration

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.rakuten.tech.mobile.inappmessaging.runtime.InApp.AppManifestConfig
import com.rakuten.tech.mobile.inappmessaging.runtime.InAppMessaging
import com.rakuten.tech.mobile.inappmessaging.runtime.UserInfoProvider
import com.rakuten.tech.mobile.inappmessaging.runtime.data.repositories.ConfigResponseRepository
import com.rakuten.tech.mobile.inappmessaging.runtime.data.repositories.HostAppInfoRepository
import com.rakuten.tech.mobile.inappmessaging.runtime.data.repositories.CampaignRepository
import com.rakuten.tech.mobile.inappmessaging.runtime.utils.Initializer
import com.rakuten.tech.mobile.inappmessaging.runtime.workmanager.schedulers.EventMessageReconciliationScheduler
import com.rakuten.tech.mobile.inappmessaging.runtime.workmanager.schedulers.MessageMixerPingScheduler
import com.rakuten.tech.mobile.inappmessaging.runtime.workmanager.workers.ConfigWorker
import com.rakuten.tech.mobile.inappmessaging.runtime.workmanager.workers.MessageMixerWorker
import org.amshove.kluent.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O_MR1])
class IntegrationSpec {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workerParameters = Mockito.mock(WorkerParameters::class.java)
    private val mockMessageScheduler = Mockito.mock(MessageMixerPingScheduler::class.java)
    private val mockEventScheduler = Mockito.mock(EventMessageReconciliationScheduler::class.java)

    @Before
    fun setup() {
        val manifest = AppManifestConfig(context)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "testid")
        ConfigResponseRepository.resetInstance()
        InAppMessaging.instance().registerPreference(object : UserInfoProvider {
            override fun provideAccessToken() = ""
            override fun provideUserId() = ""
            override fun provideIdTrackingIdentifier() = ""
        })
        // to initialize host app info
        Initializer.initializeSdk(context, manifest.subscriptionKey(), manifest.configUrl())
    }

    @Test
    fun `should return valid config`() {
        val worker = ConfigWorker(
            context, workerParameters, HostAppInfoRepository.instance(),
            ConfigResponseRepository.instance(), mockMessageScheduler
        )
        val expected = if (HostAppInfoRepository.instance().getConfigUrl().isNullOrEmpty()) {
            ListenableWorker.Result.retry()
        } else ListenableWorker.Result.success()
        worker.doWork() shouldBeEqualTo expected

        // will not work on forked repo (PR) since environment variables are not shared
        if (expected == ListenableWorker.Result.success()) {
            // confirm valid config
            ConfigResponseRepository.instance().isConfigEnabled().shouldBeTrue()
            ConfigResponseRepository.instance().getPingEndpoint().shouldNotBeNullOrEmpty()
            ConfigResponseRepository.instance().getDisplayPermissionEndpoint().shouldNotBeNullOrEmpty()
            ConfigResponseRepository.instance().getImpressionEndpoint().shouldNotBeNullOrEmpty()
        }
    }

    @Test
    fun `should return valid ping response`() {
        val worker = ConfigWorker(
            context, workerParameters, HostAppInfoRepository.instance(),
            ConfigResponseRepository.instance(), mockMessageScheduler
        )
        val result = worker.doWork()
        if (result == ListenableWorker.Result.success()) {
            val pingWorker = MessageMixerWorker(context, workerParameters, mockEventScheduler, mockMessageScheduler)
            pingWorker.doWork() shouldBeEqualTo ListenableWorker.Result.success()
            CampaignRepository.instance().lastSyncMillis?.shouldBeGreaterThan(0)
            CampaignRepository.instance().messages.shouldBeEmpty()
        } else {
            result shouldBeEqualTo ListenableWorker.Result.retry()
        }
    }
}
