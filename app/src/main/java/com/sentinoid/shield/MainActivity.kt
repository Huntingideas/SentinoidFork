package com.sentinoid.shield

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.room.Room
import com.sentinoid.shield.ui.theme.sentinoidTheme
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

class MainActivity : ComponentActivity() {
    private var db: SovereignDatabase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val passphrase = KeyManager.getOrCreatePassphrase(this)

        SQLiteDatabase.loadLibs(this)

        val factory = SupportFactory(passphrase)

        db =
            Room.databaseBuilder(
                applicationContext,
                SovereignDatabase::class.java,
                "sentinoid_ledger.db",
            )
                .openHelperFactory(SupportFactory(passphrase))
                .openHelperFactory(factory)
                .build()

        enableEdgeToEdge()
        setContent {
            sentinoidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        db?.close()
    }
}

@Composable
fun greeting(
    name: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Hello $name!",
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun greetingPreview() {
    sentinoidTheme {
        greeting("Android")
    }
}
