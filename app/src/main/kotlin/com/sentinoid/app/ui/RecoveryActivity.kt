package com.sentinoid.app.ui

import android.content.Context
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.sentinoid.app.R
import com.sentinoid.app.SentinoidApp
import com.sentinoid.app.security.RecoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStoreException
import java.util.concurrent.Executor

class RecoveryActivity : AppCompatActivity() {
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private lateinit var tvStatus: TextView
    private lateinit var recyclerShards: RecyclerView
    private lateinit var btnSetup: MaterialButton
    private lateinit var btnRecover: MaterialButton
    private lateinit var btnVerify: MaterialButton
    private lateinit var shardAdapter: ShardAdapter

    private var currentShards: List<String> = emptyList()
    private var pendingShardsForRecovery: List<String>? = null
    private var pendingWordsForRecovery: List<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recovery)

        val app = application as SentinoidApp
        recoveryManager = RecoveryManager(app.cryptoManager, app.securePreferences)

        executor = ContextCompat.getMainExecutor(this)
        setupBiometricAuth()
        setupUI()
        updateUI()
    }

    private fun setupBiometricAuth() {
        biometricPrompt =
            BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        // Retry the pending operation after successful authentication
                        pendingShardsForRecovery?.let { shards ->
                            performShardRecovery(shards)
                            pendingShardsForRecovery = null
                        }
                        pendingWordsForRecovery?.let { words ->
                            performMnemonicRecovery(words)
                            pendingWordsForRecovery = null
                        }
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Toast.makeText(
                            this@RecoveryActivity,
                            "Authentication failed",
                            Toast.LENGTH_SHORT,
                        ).show()
                        pendingShardsForRecovery = null
                        pendingWordsForRecovery = null
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        super.onAuthenticationError(errorCode, errString)
                        Toast.makeText(
                            this@RecoveryActivity,
                            "Authentication error: $errString",
                            Toast.LENGTH_LONG,
                        ).show()
                        pendingShardsForRecovery = null
                        pendingWordsForRecovery = null
                    }
                },
            )

        promptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Sentinoid Recovery")
                .setSubtitle("Authenticate to access recovery functions")
                .setNegativeButtonText("Cancel")
                .setConfirmationRequired(true)
                .build()
    }

    private fun authenticateAndExecute(operation: () -> Unit) {
        try {
            operation()
        } catch (e: Exception) {
            when (e) {
                is android.security.keystore.UserNotAuthenticatedException,
                is KeyPermanentlyInvalidatedException,
                is KeyStoreException,
                -> {
                    biometricPrompt.authenticate(promptInfo)
                }
                else -> throw e
            }
        }
    }

    private fun setupUI() {
        tvStatus = findViewById(R.id.tv_recovery_status)
        recyclerShards = findViewById(R.id.recycler_shards)
        btnSetup = findViewById(R.id.btn_setup_recovery)
        btnRecover = findViewById(R.id.btn_recover)
        btnVerify = findViewById(R.id.btn_verify)

        shardAdapter = ShardAdapter()
        recyclerShards.layoutManager = LinearLayoutManager(this)
        recyclerShards.adapter = shardAdapter

        btnSetup.setOnClickListener { setupRecovery() }
        btnRecover.setOnClickListener { showRecoverDialog() }
        btnVerify.setOnClickListener { showVerifyDialog() }
    }

    private fun updateUI() {
        if (recoveryManager.isRecoverySetup()) {
            tvStatus.text = getString(R.string.status_configured)
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            btnSetup.text = getString(R.string.btn_resetup_recovery)
        } else {
            tvStatus.text = getString(R.string.status_not_configured)
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            btnSetup.text = getString(R.string.btn_setup_recovery)
        }

        loadShards()
    }

    private fun loadShards() {
        authenticateAndExecute {
            lifecycleScope.launch(Dispatchers.IO) {
                val shards =
                    try {
                        (1..3).mapNotNull { index ->
                            recoveryManager.getStoredShard(index)
                        }
                    } catch (e: Exception) {
                        emptyList<String>()
                    }

                withContext(Dispatchers.Main) {
                    currentShards = shards
                    shardAdapter.submitList(shards)

                    // Show empty state message if no shards
                    if (shards.isEmpty() && recoveryManager.isRecoverySetup()) {
                        tvStatus.text = "Shards encrypted - authenticate to view"
                    }
                }
            }
        }
    }

    private fun setupRecovery() {
        AlertDialog.Builder(this)
            .setTitle("Setup Recovery")
            .setMessage(
                "This will generate a 24-word mnemonic and split it into 3 shards (2-of-3 required for recovery). " +
                    "Write down your shards and store them in separate secure locations.",
            )
            .setPositiveButton("Continue") { _, _ ->
                performSetup()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSetup() {
        authenticateAndExecute {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val setup = recoveryManager.setupRecovery()

                    withContext(Dispatchers.Main) {
                        showSetupResults(setup.mnemonicWords, setup.shardStrings)
                        updateUI()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@RecoveryActivity,
                            "Setup failed: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun showSetupResults(
        words: List<String>,
        shards: List<String>,
    ) {
        val message =
            buildString {
                appendLine("=== MNEMONIC WORDS (24 words) ===")
                words.chunked(4).forEach { chunk ->
                    appendLine(chunk.joinToString(" "))
                }
                appendLine()
                appendLine("=== SHARDS (2 of 3 required) ===")
                shards.forEachIndexed { index, shard ->
                    appendLine("Shard ${index + 1}: $shard")
                    appendLine()
                }
            }

        AlertDialog.Builder(this)
            .setTitle("SAVE YOUR RECOVERY DATA")
            .setMessage(message)
            .setPositiveButton("I Have Saved These") { _, _ -> }
            .setCancelable(false)
            .show()
    }

    private fun showRecoverDialog() {
        val options = arrayOf("Recover from Shards", "Recover from Mnemonic")

        AlertDialog.Builder(this)
            .setTitle("Select Recovery Method")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showShardRecoveryDialog()
                    1 -> showMnemonicRecoveryDialog()
                }
            }
            .show()
    }

    private fun showShardRecoveryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_shard_recovery, null)
        val etShard1 = view.findViewById<TextInputEditText>(R.id.et_shard1)
        val etShard2 = view.findViewById<TextInputEditText>(R.id.et_shard2)
        val etShard3 = view.findViewById<TextInputEditText>(R.id.et_shard3)

        AlertDialog.Builder(this)
            .setTitle("Enter 2 or 3 Shards")
            .setView(view)
            .setPositiveButton("Recover") { _, _ ->
                val shards =
                    listOfNotNull(
                        etShard1.text?.toString()?.trim(),
                        etShard2.text?.toString()?.trim(),
                        etShard3.text?.toString()?.trim(),
                    ).filter { it.isNotEmpty() }

                if (shards.size < 2) {
                    Toast.makeText(this, "Please enter at least 2 shards", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performShardRecovery(shards)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMnemonicRecoveryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_mnemonic_recovery, null)
        val etMnemonic = view.findViewById<TextInputEditText>(R.id.et_mnemonic)

        AlertDialog.Builder(this)
            .setTitle("Enter 24-Word Mnemonic")
            .setView(view)
            .setPositiveButton("Recover") { _, _ ->
                val mnemonic = etMnemonic.text?.toString() ?: ""
                val words = mnemonic.split(" ", "\n").filter { it.isNotBlank() }
                if (words.size != 24) {
                    Toast.makeText(this, "Please enter all 24 words", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performMnemonicRecovery(words)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performShardRecovery(shards: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = recoveryManager.recoverFromShards(shards)

            withContext(Dispatchers.Main) {
                when (result) {
                    is RecoveryManager.RecoveryResult.Success -> showRecoverySuccess()
                    is RecoveryManager.RecoveryResult.Error ->
                        Toast.makeText(
                            this@RecoveryActivity,
                            "Recovery failed: ${result.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    private fun performMnemonicRecovery(words: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = recoveryManager.recoverFromMnemonic(words)

            withContext(Dispatchers.Main) {
                when (result) {
                    is RecoveryManager.RecoveryResult.Success -> showRecoverySuccess()
                    is RecoveryManager.RecoveryResult.Error ->
                        Toast.makeText(
                            this@RecoveryActivity,
                            "Recovery failed: ${result.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    private fun showRecoverySuccess() {
        AlertDialog.Builder(this)
            .setTitle("Recovery Successful")
            .setMessage("Your vault has been successfully recovered.")
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showVerifyDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_verify_shard, null)
        val etShard = view.findViewById<TextInputEditText>(R.id.et_shard_verify)

        AlertDialog.Builder(this)
            .setTitle("Verify Shard")
            .setView(view)
            .setPositiveButton("Verify") { _, _ ->
                val shard = etShard.text?.toString() ?: ""
                val isValid = recoveryManager.validateShard(shard)

                AlertDialog.Builder(this)
                    .setTitle("Verification Result")
                    .setMessage(if (isValid) "✓ Shard is valid" else "✗ Invalid shard format")
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class ShardAdapter : RecyclerView.Adapter<ShardAdapter.ViewHolder>() {
        private var shards: List<String> = emptyList()

        fun submitList(newShards: List<String>) {
            shards = newShards
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val view =
                LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val shard = shards[position]
            holder.bind(position + 1, shard)
        }

        override fun getItemCount() = shards.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val titleView: TextView = view.findViewById(android.R.id.text1)
            private val contentView: TextView = view.findViewById(android.R.id.text2)

            fun bind(
                shardNumber: Int,
                shard: String,
            ) {
                titleView.text = "Shard $shardNumber (2-of-3 required)"
                contentView.text = shard
                contentView.setTextIsSelectable(true)
                contentView.setOnLongClickListener {
                    copyToClipboard("Shard $shardNumber", shard)
                    Toast.makeText(itemView.context, "Shard $shardNumber copied!", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            private fun copyToClipboard(
                label: String,
                text: String,
            ) {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(label, text)
                clipboard.setPrimaryClip(clip)
            }
        }
    }
}
