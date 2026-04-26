package com.example.mediconnect_ai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatMessage::class,
        Patient::class,
        NotificationHistory::class,
        Task::class,
        Pregnancy::class,
        VaccineStatus::class,
        ANCVisit::class,
        PNCVisit::class,
        TBProfile::class,   // ✅ NEW
        TBVisit::class,     // ✅ NEW
        PatientVitalRecord::class
    ],
    version = 16,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun patientDao(): PatientDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun taskDao(): TaskDao
    abstract fun pregnancyDao(): PregnancyDao
    abstract fun vaccineStatusDao(): VaccineStatusDao
    abstract fun ancVisitDao(): ANCVisitDao
    abstract fun pncVisitDao(): PNCVisitDao
    abstract fun tbDao(): TBDao // ✅ NEW
    abstract fun patientVitalRecordDao(): PatientVitalRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // --- OLD MIGRATIONS (Preserved for logic, though destructive migration is enabled below) ---

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `patients_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fullName` TEXT NOT NULL,
                        `age` INTEGER NOT NULL,
                        `gender` TEXT NOT NULL,
                        `contactNumber` TEXT NOT NULL,
                        `address` TEXT NOT NULL,
                        `registrationTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `patients_table` ADD COLUMN `preExistingConditions` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `patients_table` ADD COLUMN `aadhaarNumber` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_history_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS appointments_table")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tasks_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `patientId` INTEGER NOT NULL,
                        `patientName` TEXT NOT NULL,
                        `taskDescription` TEXT NOT NULL,
                        `dueDate` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pregnancies_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `patientId` INTEGER NOT NULL,
                        `patientName` TEXT NOT NULL,
                        `lmpDate` INTEGER NOT NULL,
                        `edd` INTEGER NOT NULL,
                        `isActive` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vaccine_status` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `patientId` INTEGER NOT NULL,
                        `vaccineName` TEXT NOT NULL,
                        `dueDate` INTEGER NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'Scheduled',
                        FOREIGN KEY(`patientId`) REFERENCES `patients_table`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE patients_table ADD COLUMN profileImageUri TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE patients_table ADD COLUMN `isReferred` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE patients_table ADD COLUMN `referralReason` TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `anc_visit_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `pregnancyId` INTEGER NOT NULL,
                        `visitName` TEXT NOT NULL,
                        `dueWeekStart` INTEGER NOT NULL,
                        `dueWeekEnd` INTEGER NOT NULL,
                        `dueDate` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `completionDate` INTEGER,
                        `notes` TEXT,
                        FOREIGN KEY(`pregnancyId`) REFERENCES `pregnancies_table`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_anc_visit_table_pregnancyId` ON `anc_visit_table` (`pregnancyId`)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `patients_table` ADD COLUMN `latitude` REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE `patients_table` ADD COLUMN `longitude` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `patients_table` ADD COLUMN `familyId` TEXT")
                database.execSQL("ALTER TABLE `patients_table` ADD COLUMN `householdSize` INTEGER")
                database.execSQL("ALTER TABLE `patients_table` ADD COLUMN `isHeadOfFamily` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `pregnancies_table` ADD COLUMN `deliveryDate` INTEGER DEFAULT NULL")
                database.execSQL("CREATE TABLE IF NOT EXISTS `pnc_visit_table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `patientId` INTEGER NOT NULL, `visitName` TEXT NOT NULL, `dayNumber` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, `completionDate` INTEGER, `motherBP` TEXT, `motherBleeding` INTEGER NOT NULL, `babyWeight` REAL, `babyFeeding` TEXT, FOREIGN KEY(`patientId`) REFERENCES `patients_table`(`id`) ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_pnc_visit_table_patientId` ON `pnc_visit_table` (`patientId`)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // ✅ UPDATED TB PROFILE TABLE (Added diagnosisDate)
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tb_profile_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `patientId` INTEGER NOT NULL,
                        `nikshayId` TEXT,
                        `diagnosisDate` INTEGER NOT NULL, 
                        `tbType` TEXT NOT NULL,
                        `drugResistanceType` TEXT NOT NULL DEFAULT 'DS',
                        `treatmentStartDate` INTEGER NOT NULL,
                        `continuationPhaseStartDate` INTEGER,
                        `treatmentDurationMonths` INTEGER NOT NULL DEFAULT 6,
                        `treatmentPhase` TEXT NOT NULL,
                        `lastFollowUpDate` INTEGER,
                        `adherenceRisk` INTEGER NOT NULL DEFAULT 0,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `outcome` TEXT,
                        `dbtStatus` TEXT,
                        FOREIGN KEY(`patientId`) REFERENCES `patients_table`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_tb_profile_table_patientId` ON `tb_profile_table` (`patientId`)")

                // ✅ TB VISIT TABLE
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tb_visit_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tbProfileId` INTEGER NOT NULL,
                        `visitType` TEXT NOT NULL,
                        `treatmentPhase` TEXT NOT NULL,
                        `dueDate` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL DEFAULT 0,
                        `completionDate` INTEGER,
                        `medicinesTaken` INTEGER,
                        `missedDoseCount` INTEGER NOT NULL DEFAULT 0,
                        `symptomsNotes` TEXT,
                        `sideEffects` INTEGER NOT NULL DEFAULT 0,
                        `weightKg` REAL,
                        `needsReferral` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`tbProfileId`) REFERENCES `tb_profile_table`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_tb_visit_table_tbProfileId` ON `tb_visit_table` (`tbProfileId`)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `patient_vitals_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` TEXT,
                        `patientId` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `recordedAt` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `systolicBp` REAL,
                        `diastolicBp` REAL,
                        `hemoglobin` REAL,
                        `weightKg` REAL,
                        `spo2` REAL,
                        `pulse` REAL,
                        `gestationalWeek` INTEGER,
                        `muacCm` REAL,
                        `wazScore` REAL,
                        `temperature` REAL,
                        `ageMonths` INTEGER,
                        `coughSeverity` REAL,
                        `nightSweatsScore` REAL,
                        `missedDosesWeek` INTEGER,
                        `treatmentMonth` INTEGER,
                        `fastingGlucose` REAL,
                        `bmi` REAL,
                        `notes` TEXT
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_patient_vitals_table_patientId_category_recordedAt` ON `patient_vitals_table` (`patientId`, `category`, `recordedAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_patient_vitals_table_userId_recordedAt` ON `patient_vitals_table` (`userId`, `recordedAt`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mediconnect_database"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16
                    )
                    .fallbackToDestructiveMigration() // ✅ Allows Room to rebuild DB if migrations fail
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}