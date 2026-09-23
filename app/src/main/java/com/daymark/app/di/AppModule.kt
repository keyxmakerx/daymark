package com.daymark.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Named
import com.daymark.app.data.AppDatabase
import com.daymark.app.data.JournalEncryptionGate
import com.daymark.app.data.SqlCipherLibrary
import com.daymark.app.data.dao.ActivityDao
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.dao.GoalDao
import com.daymark.app.data.dao.JournalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * THE ONE PLACE THE JOURNAL IS OPENED, AND THE ONE PLACE IT IS DECIDED HOW.
     *
     * [JournalEncryptionGate.rawKeyForRoom] does three things before this line is reached: it gets
     * or makes the data key, it runs the plaintext-to-encrypted migration if one is owed, and it
     * says what the file on disk actually is now. Only when the answer is "encrypted" does Room get
     * the SQLCipher factory.
     *
     * WHY THE NULL BRANCH OPENS THE FILE THE OLD WAY RATHER THAN REFUSING. A migration that failed
     * left the plaintext database exactly where it was, and handing that file to a factory holding a
     * key produces "file is not a database" — the app would stop working over a problem that has
     * already been recovered from. So a failed migration means the app carries on as it always did
     * and tries again on the next launch. The person loses nothing, and the settings copy is the
     * thing that has to be honest about it.
     *
     * NOTHING HERE HAS EVER RUN. There is no Android SDK on the development machine and no device
     * in CI, so this compiles and stops. First phone, backup first.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        journalEncryption: JournalEncryptionGate,
    ): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .apply {
                journalEncryption.rawKeyForRoom()?.let { rawKey ->
                    SqlCipherLibrary.ensureLoaded()
                    // The bytes are the ASCII of x'<64 hex>', which is what SQLCipher recognises as
                    // a RAW key. Any other spelling and it runs its own PBKDF2 over them on every
                    // single open. See DataKeyWraps.rawKeySpelling.
                    openHelperFactory(SupportOpenHelperFactory(rawKey))
                }
            }
            .addCallback(AppDatabase.SeedCallback())
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                // Every migration declared on AppDatabase must appear in this list. A migration
                // written and not registered here is invisible until an upgrade: Room finds no path
                // from the installed version to the new one and throws IllegalStateException on the
                // first database access, on the phone of someone who already had data. New installs
                // are unaffected, which is why it survives testing.
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
            )
            .build()

    @Provides
    fun provideEntryDao(db: AppDatabase): EntryDao = db.entryDao()

    @Provides
    fun provideActivityDao(db: AppDatabase): ActivityDao = db.activityDao()

    @Provides
    fun provideJournalDao(db: AppDatabase): JournalDao = db.journalDao()

    @Provides
    fun provideGoalDao(db: AppDatabase): GoalDao = db.goalDao()

    /**
     * Added when `SkyRepository` became the first class to take a [GoalStepDao] as a **constructor**
     * parameter. `GoalRepository` had reached the same DAO through `database.goalStepDao()` in its
     * class body, which needs no binding — so the graph had been complete by accident, and the
     * omission only surfaced as a Dagger `MissingBinding` at annotation-processing time.
     */
    @Provides
    fun provideGoalStepDao(db: AppDatabase): com.daymark.app.data.dao.GoalStepDao = db.goalStepDao()

    @Provides
    fun provideSleepLogDao(db: AppDatabase): com.daymark.app.data.dao.SleepLogDao = db.sleepLogDao()

    @Provides
    fun provideTreatmentDao(db: AppDatabase): com.daymark.app.data.dao.TreatmentDao = db.treatmentDao()

    @Provides
    fun provideTrackerDao(db: AppDatabase): com.daymark.app.data.dao.TrackerDao = db.trackerDao()

    @Provides
    fun provideTrackerLogDao(db: AppDatabase): com.daymark.app.data.dao.TrackerLogDao = db.trackerLogDao()

    @Provides
    fun provideReminderDao(db: AppDatabase): com.daymark.app.data.dao.ReminderDao = db.reminderDao()

    @Provides
    fun provideAssessmentDao(db: AppDatabase): com.daymark.app.data.dao.AssessmentDao = db.assessmentDao()

    @Provides
    fun provideThoughtRecordDao(db: AppDatabase): com.daymark.app.data.dao.ThoughtRecordDao = db.thoughtRecordDao()

    @Provides
    fun provideSafetyPlanDao(db: AppDatabase): com.daymark.app.data.dao.SafetyPlanDao = db.safetyPlanDao()

    @Provides
    fun provideOfferRecordDao(db: AppDatabase): com.daymark.app.data.dao.OfferRecordDao = db.offerRecordDao()

    @Provides
    fun provideLifeEventDao(db: AppDatabase): com.daymark.app.data.dao.LifeEventDao = db.lifeEventDao()

    @Provides
    fun providePersonDao(db: AppDatabase): com.daymark.app.data.dao.PersonDao = db.personDao()

    @Provides
    fun providePersonNoteDao(db: AppDatabase): com.daymark.app.data.dao.PersonNoteDao = db.personNoteDao()

    /**
     * The link from an entry to the people it names.
     *
     * A binding of its own because the link is a DAO of its own, which is the point:
     * `docs/FEATURES.md` §11.2 keeps a person away from anything that reads mood, and `EntryDao` —
     * the one that returns `moodLevel` — has no method that touches `entry_people`.
     * See `EntryPersonDao`'s header.
     */
    @Provides
    fun provideEntryPersonDao(db: AppDatabase): com.daymark.app.data.dao.EntryPersonDao = db.entryPersonDao()

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("daymark_settings", Context.MODE_PRIVATE)

    /** AES-256 encrypted store for sensitive material (the PIN hash). */
    @Provides
    @Singleton
    @Named("secure")
    fun provideSecurePreferences(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "daymark_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
