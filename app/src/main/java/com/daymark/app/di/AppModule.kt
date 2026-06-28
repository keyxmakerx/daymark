package com.daymark.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Named
import com.daymark.app.data.AppDatabase
import com.daymark.app.data.dao.ActivityDao
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
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
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("daylie_settings", Context.MODE_PRIVATE)

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
            "daylie_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
