package app.convarchive

import android.app.Application
import app.convarchive.data.AppDatabase
import app.convarchive.data.Repository

/**
 * Application subclass. Initializes the Room database singleton lazily;
 * screens obtain the [Repository] via `(application as ConvArchiveApp).repository`.
 */
class ConvArchiveApp : Application() {

    /** Room database singleton. */
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    /** Repository wrapping the DAO; all calls must run on Dispatchers.IO. */
    val repository: Repository by lazy { Repository(db.conversationDao()) }
}
