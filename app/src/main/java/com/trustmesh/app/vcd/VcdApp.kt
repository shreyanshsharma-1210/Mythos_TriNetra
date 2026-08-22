package com.trustmesh.app.vcd

import android.app.Application
import androidx.room.Room
import com.trustmesh.app.vcd.data.ContactRepository
import com.trustmesh.app.vcd.data.db.VcdDatabase
import com.trustmesh.app.vcd.ml.ModelRuntime
import com.trustmesh.app.vcd.service.Notifications

/**
 * Hand-rolled container. A DI framework would earn its keep in a larger app; here it would be one
 * more thing to explain during a demo for three singletons.
 */
open class VcdApp : Application() {

    val models: ModelRuntime by lazy { ModelRuntime(this) }

    private val database: VcdDatabase by lazy {
        Room.databaseBuilder(this, VcdDatabase::class.java, "vcd.db")
            // No fallbackToDestructiveMigration: silently dropping the table would delete
            // voiceprints without the user ever asking, and deletion here is supposed to be an
            // explicit, deliberate act.
            .addMigrations(
                VcdDatabase.MIGRATION_1_2,
                VcdDatabase.MIGRATION_2_3,
                VcdDatabase.MIGRATION_3_4,
            )
            .build()
    }

    val callHistory by lazy { database.callHistoryDao() }

    val contacts: ContactRepository by lazy {
        ContactRepository(database.contactDao()) { models.modelId() }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }
}
