/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.uber.rxdogtag.RxDogTag
import com.uber.rxdogtag.autodispose.AutoDisposeConfigurer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.HasBroadcastReceiverInjector
import dagger.android.HasServiceInjector
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.FileLoggingTree
import dev.octoshrimpy.quik.injection.AppComponentManager
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.interactor.SpeakThreads
import dev.octoshrimpy.quik.manager.BillingManager
import dev.octoshrimpy.quik.manager.ReferralManager
import dev.octoshrimpy.quik.migration.QkMigration
import dev.octoshrimpy.quik.migration.QkRealmMigration
import dev.octoshrimpy.quik.util.NightModeManager
import dev.octoshrimpy.quik.worker.CommandWorker
import dev.octoshrimpy.quik.worker.HousekeepingWorker
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import dev.octoshrimpy.quik.common.widget.ControlShapeDrawable
import dev.octoshrimpy.quik.util.Preferences

class QKApplication : Application(), HasActivityInjector, HasBroadcastReceiverInjector, HasServiceInjector {

    /**
     * Inject these so that they are forced to initialize
     */
    @Suppress("unused")
    @Inject lateinit var qkMigration: QkMigration

    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>
    @Inject lateinit var dispatchingBroadcastReceiverInjector: DispatchingAndroidInjector<BroadcastReceiver>
    @Inject lateinit var dispatchingServiceInjector: DispatchingAndroidInjector<Service>
    @Inject lateinit var fileLoggingTree: FileLoggingTree
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var realmMigration: QkRealmMigration
    @Inject lateinit var referralManager: ReferralManager
    @Inject lateinit var workerFactory: WorkerFactory

    override fun onCreate() {
        super.onCreate()

        // set translated "no messages" string for speakThreads interactor
        SpeakThreads.setNoMessagesString(getString(R.string.speak_no_messages))

        AppComponentManager.init(this)
        appComponent.inject(this)

        Realm.init(this)
        Realm.setDefaultConfiguration(RealmConfiguration.Builder()
                .compactOnLaunch()
                .migration(realmMigration)
                .schemaVersion(QkRealmMigration.SCHEMA_VERSION)
                .build())

        qkMigration.performMigration()

        GlobalScope.launch(Dispatchers.IO) {
            referralManager.trackReferrer()
            billingManager.checkForPurchases()
            billingManager.queryProducts()
        }

        nightModeManager.updateCurrentTheme()

        // ControlShapeDrawable is built by the resource system without a Context, so it reads
        // a static; keep that static equal to the preference for the life of the process.
        prefs.controlShape.asObservable().subscribe { shape -> ControlShapeDrawable.shape = shape }

        // configure timber logging
        Timber.plant(Timber.DebugTree(), fileLoggingTree)

        // configure emoji compatibility with bundled package
        // (bundled library works with no play-services/gsm os versions)
        EmojiCompat.init(BundledEmojiCompatConfig(this)
            .registerInitCallback(object: EmojiCompat.InitCallback() {
                override fun onInitialized() {
                    super.onInitialized()
                    Timber.v("bundled emojicompat initialized")
                }

                override fun onFailed(throwable: Throwable?) {
                    super.onFailed(throwable)
                    Timber.e("bundled emojicompat initialization failed")
                }
            })
        )

        // rxdogtag provides 'look-back' for exceptions in rxjava2 'chains'
        RxDogTag.builder()
                .configureWith(AutoDisposeConfigurer::configure)
                .install()

        // init work manager with custom factory supporting dagger/injection capability
        WorkManager.initialize(
            this,
            Configuration.Builder().setWorkerFactory(workerFactory).build()
        )

        // register, or re-register, housekeeping work manager
        HousekeepingWorker.register(applicationContext)

        // sms-bridge: drain whatever the desktop queued.
        //
        // Two calls on purpose. The periodic worker is the quiet-case safety net, but
        // it cannot also be the prompt path: re-registering periodic work on every
        // launch recalculates its window, so a frequently-opened app pushes the next
        // run out indefinitely and the queue never drains. The one-shot below is what
        // actually makes opening the app feel immediate.
        //
        // Both are inert unless the bridge is configured in settings.
        //
        // enqueueFresh, not enqueue: after a reinstall the unique record can be wedged
        // ENQUEUED with its platform job gone, and KEEP would no-op forever (see the
        // policy notes in CommandWorker). Process start is the safe place to REPLACE.
        CommandWorker.schedulePeriodic(applicationContext)
        CommandWorker.enqueueFresh(applicationContext)
    }

    override fun activityInjector(): AndroidInjector<Activity> {
        return dispatchingActivityInjector
    }

    override fun broadcastReceiverInjector(): AndroidInjector<BroadcastReceiver> {
        return dispatchingBroadcastReceiverInjector
    }

    override fun serviceInjector(): AndroidInjector<Service> {
        return dispatchingServiceInjector
    }

}