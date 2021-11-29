/*
 * Copyright (C) 2021 Bernhard Rauch.
 *
 * This file is part of Mooltifill.
 *
 * Mooltifill is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Mooltifill is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mooltifill.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.mathfactory.mooltifill

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlin.random.Random

private sealed class CredentialResult(val credentials: Credentials?) {
    object InvalidQuery: CredentialResult(null)
    object DeviceNotFound: CredentialResult(null)
    object CommFail: CredentialResult(null)
    object NoItem: CredentialResult(null)
    object ParseFail: CredentialResult(null)
    class Item(credentials: Credentials) : CredentialResult(credentials)
}

class MooltifillActivity : AppCompatActivity() {

    @ExperimentalCoroutinesApi
    @FlowPreview
    companion object {
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_SAVE = "extra_save"

        private suspend fun getCredentials(context: Context, query: String): CredentialResult {
            if(query.isBlank()) return CredentialResult.InvalidQuery
            val f = BleMessageFactory()
            val device = MooltipassDevice.connect(context) ?: return CredentialResult.DeviceNotFound // "Mooltipass device not accessible"
            device.send(MooltipassPayload.FLIP_BIT_RESET_PACKET)
            val credGet = MooltipassMessage(MooltipassCommand.GET_CREDENTIAL_BLE, MooltipassPayload.getCredentials(query, null))
            val credGetAnswer = device.communicate(f.serialize(credGet))?.let(f::deserialize)
            if(MooltipassCommand.GET_CREDENTIAL_BLE != credGetAnswer?.cmd) return CredentialResult.CommFail  // "Reading failed"
            if(credGetAnswer.data?.isEmpty() != false) return CredentialResult.NoItem // "No item found"
            return MooltipassPayload.answerGetCredentials(query, credGetAnswer.data)?.let { CredentialResult.Item(it) }
                ?: CredentialResult.ParseFail
        }

        suspend fun setCredentials(context: Context, service: String, login: String, pass: String): Boolean {
            if(service.isBlank()) return false
            val f = BleMessageFactory()
            val device = MooltipassDevice.connect(context) ?: return false // "Mooltipass device not accessible"
            device.send(MooltipassPayload.FLIP_BIT_RESET_PACKET)
            val cred = MooltipassMessage(MooltipassCommand.STORE_CREDENTIAL_BLE, MooltipassPayload.storeCredentials(service, login, null, null, pass))
            val credAnswer = device.communicate(f.serialize(cred))?.let(f::deserialize)

            if(MooltipassCommand.STORE_CREDENTIAL_BLE != credAnswer?.cmd) return false // "Command failed"
            if(credAnswer.data?.size != 1) return false
            if(credAnswer.data[0].toInt() != 1) return false // "Item save failed"

            return true
        }

        suspend fun ping(context: Context): Boolean {
            val f = BleMessageFactory()
            val device = MooltipassDevice.connect(context) ?: return false
            device.send(MooltipassPayload.FLIP_BIT_RESET_PACKET)
            val random = List(4) { Random.nextInt(0, 256) }
            val ping = MooltipassMessage(MooltipassCommand.PING_BLE, random)
            val answer = device.communicate(f.serialize(ping))?.let(f::deserialize) ?: return false
            return answer.cmd == MooltipassCommand.PING_BLE && ping.data contentEquals answer.data
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mooltifill)
        intent?.getStringExtra(EXTRA_QUERY)?.let { query ->
            val save = intent?.getBooleanExtra(EXTRA_SAVE, false) ?: false
            if (save) {
                // save is handled by MooltifillService
            } else /* query */ {
                findViewById<TextView>(R.id.textView)?.text = "Query: " + (query ?: "<?>")
                CoroutineScope(Dispatchers.IO).launch {
                    val reply = getCredentials(applicationContext, query)
                    when(reply) {
                        is CredentialResult.Item -> {}
                        is CredentialResult.NoItem -> withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "No credentials found", Toast.LENGTH_SHORT).show()
                            delay(2000)
                        }
                        else -> {
                            withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Error retrieving credentials", Toast.LENGTH_SHORT).show() }
                            if(SettingsActivity.isDebugEnabled(applicationContext)) {
                                Log.d("Mooltifill", reply.toString())
                            }
                            delay(2000)
                        }
                    }
                    sendReply(reply.credentials)
                }
            }
        }
    }

    private fun sendReply(reply: Credentials?) {
        val login = intent.getParcelableExtra<AutofillId>(EXTRA_USERNAME)
        val pass = intent.getParcelableExtra<AutofillId>(EXTRA_PASSWORD)
        if(reply?.login == null || reply.password == null || login == null || pass == null) {
            setResult(RESULT_CANCELED)
            finish()
        } else {
            val replyIntent = Intent()
            val builder = Dataset.Builder()
                .setValue(login, AutofillValue.forText(reply.login))
                .setValue(pass, AutofillValue.forText(reply.password))
            replyIntent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build())

            setResult(RESULT_OK, replyIntent)
            finish()

        }
    }
}