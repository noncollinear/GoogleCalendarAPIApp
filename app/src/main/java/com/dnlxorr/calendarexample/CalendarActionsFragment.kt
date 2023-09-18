package com.dnlxorr.calendarexample

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dnlxorr.calendarexample.databinding.FragmentCalendarActionsBinding
import com.dnlxorr.calendarexample.model.EventModel
import com.dnlxorr.calendarexample.util.Constants.PREF_ACCOUNT_NAME
import com.dnlxorr.calendarexample.util.Constants.REQUEST_ACCOUNT_PICKER
import com.dnlxorr.calendarexample.util.Constants.REQUEST_AUTHORIZATION
import com.dnlxorr.calendarexample.util.Constants.REQUEST_GOOGLE_PLAY_SERVICES
import com.dnlxorr.calendarexample.util.Constants.REQUEST_PERMISSION_GET_ACCOUNTS
import com.dnlxorr.calendarexample.util.executeAsyncTask
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventAttendee
import com.google.api.services.calendar.model.EventDateTime
import kotlinx.coroutines.cancel
import pub.devrel.easypermissions.EasyPermissions
import java.io.IOException
import java.util.Arrays


class CalendarActionsFragment : Fragment() {

    private var _binding: FragmentCalendarActionsBinding? = null
    private val binding get() = _binding!!


    private var mCredential: GoogleAccountCredential? = null //To access our account
    private var mService: Calendar? = null //To access the calendar

    var mProgress: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initCredentials()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentCalendarActionsBinding.inflate(inflater, container, false)

        initView()

        return binding.root
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_GOOGLE_PLAY_SERVICES -> if (resultCode != Activity.RESULT_OK) {
                binding.txtOut.text =
                    "This app requires Google Play Services. Please install " + "Google Play Services on your device and relaunch this app."
            } else {
                getResultsFromApi()
            }
            REQUEST_ACCOUNT_PICKER -> if (resultCode == Activity.RESULT_OK && data != null &&
                data.extras != null
            ) {
                val accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (accountName != null) {
                    val settings = this.activity?.getPreferences(Context.MODE_PRIVATE)
                    val editor = settings?.edit()
                    editor?.putString(PREF_ACCOUNT_NAME, accountName)
                    editor?.apply()
                    mCredential!!.selectedAccountName = accountName
                    getResultsFromApi()
                }
            }
            REQUEST_AUTHORIZATION -> if (resultCode == Activity.RESULT_OK) {
                getResultsFromApi()
            }
        }
    }

    private fun initView() {
        mProgress = ProgressDialog(requireContext())
        mProgress!!.setMessage("Loading...")

        with(binding) {
            btnGetCalendars.setOnClickListener {
                btnInsertCalendar.isEnabled = false
                btnGetCalendars.isEnabled = false
                txtOut.text = ""
                getResultsFromApi()
                btnGetCalendars.isEnabled = true
                btnInsertCalendar.isEnabled = true

            }

            btnInsertCalendar.setOnClickListener {
                btnGetCalendars.isEnabled = false
                btnInsertCalendar.isEnabled = false
                txtOut.text = ""
                makeInsertionTask()
                btnGetCalendars.isEnabled = true
                btnInsertCalendar.isEnabled = true
            }
        }
    }

    private fun initCredentials() {
        mCredential = GoogleAccountCredential.usingOAuth2(
            requireContext(),
            arrayListOf(CalendarScopes.CALENDAR)
        )
            .setBackOff(ExponentialBackOff())
        initCalendarBuild(mCredential)
    }

    private fun initCalendarBuild(credential: GoogleAccountCredential?) {
        val transport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = JacksonFactory.getDefaultInstance()
        mService = Calendar.Builder(
            transport, jsonFactory, credential
        )
            .setApplicationName("CalendarExample")
            .build()
    }

    private fun getResultsFromApi() {
        if (!isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices()
        } else if (mCredential!!.selectedAccountName == null) {
            chooseAccount()
        } else if (!isDeviceOnline()) {
            binding.txtOut.text = "No network connection available."
        } else {
            makeRequestTask()
        }
    }

    private fun chooseAccount() {
        if (EasyPermissions.hasPermissions(
                requireContext(), Manifest.permission.GET_ACCOUNTS
            )
        ) {
            val accountName = this.activity?.getPreferences(Context.MODE_PRIVATE)
                ?.getString(PREF_ACCOUNT_NAME, null)
            if (accountName != null) {
                mCredential!!.selectedAccountName = accountName
                getResultsFromApi()
            } else {
                // Start a dialog from which the user can choose an account
                startActivityForResult(
                    mCredential!!.newChooseAccountIntent(),
                    REQUEST_ACCOUNT_PICKER
                )
            }
        } else {
            // Request the GET_ACCOUNTS permission via a user dialog
            EasyPermissions.requestPermissions(
                this,
                "This app needs to access your Google account (via Contacts).",
                REQUEST_PERMISSION_GET_ACCOUNTS,
                Manifest.permission.GET_ACCOUNTS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    //I check if there is Google console access permission.
    private fun isGooglePlayServicesAvailable(): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(requireContext())
        return connectionStatusCode == ConnectionResult.SUCCESS
    }

    //Checks whether the device supports Google play services
    private fun acquireGooglePlayServices() {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(requireContext())
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode)
        }
    }

    fun showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode: Int) {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val dialog = apiAvailability.getErrorDialog(
            this,
            connectionStatusCode,
            REQUEST_GOOGLE_PLAY_SERVICES
        )
        dialog?.show()
    }

    private fun isDeviceOnline(): Boolean {
        val connMgr =
            this.activity?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connMgr.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }

    private fun makeRequestTask() {
        var mLastError: Exception? = null

        lifecycleScope.executeAsyncTask(
            onStart = {
                mProgress!!.show()
            },
            doInBackground = {
                try {
                    getDataFromCalendar()
                } catch (e: Exception) {
                    mLastError = e
                    lifecycleScope.cancel()
                    null
                }
            },
            onPostExecute = { output ->
                mProgress!!.hide()
                if (output == null || output.size == 0) {
                    Log.d("Google", "no data")
                } else {
                    for (index in 0 until output.size) {
                        binding.txtOut.text = (TextUtils.join("\n", output))
                        Log.d(
                            "Google",
                            output[index].id.toString() + " " + output[index].summary + " " + output[index].startDate
                        )
                    }
                }
            },
            onCancelled = {
                mProgress!!.hide()
                if (mLastError != null) {
                    if (mLastError is GooglePlayServicesAvailabilityIOException) {
                        showGooglePlayServicesAvailabilityErrorDialog(
                            (mLastError as GooglePlayServicesAvailabilityIOException)
                                .connectionStatusCode
                        )
                    } else if (mLastError is UserRecoverableAuthIOException) {
                        this.startActivityForResult(
                            (mLastError as UserRecoverableAuthIOException).intent,
                            REQUEST_AUTHORIZATION
                        )
                    } else {
                        binding.txtOut.text = "The following error occurred:\n" + mLastError!!.message
                    }
                } else {
                    binding.txtOut.text = "Request cancelled."
                }
            }
        )
    }

    fun getDataFromCalendar(): MutableList<EventModel> {
        val now = DateTime(System.currentTimeMillis())
        val eventStrings = ArrayList<EventModel>()
        try {
            val events = mService!!.events().list("primary")
                .setMaxResults(10)
                .setTimeMin(now)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute()
            val items = events.items

            for (event in items) {
                var start = event.start.dateTime
                if (start == null) {
                    start = event.start.date
                }

                eventStrings.add(
                    EventModel(
                        summary = event.summary,
                        startDate = start.toString()
                    )
                )
            }
            return eventStrings

        } catch (e: IOException) {
            Log.d("Google", e.message.toString())
            if (e is UserRecoverableAuthIOException) {
                this.startActivityForResult(e.intent, REQUEST_AUTHORIZATION)
            }
        }
        return eventStrings
    }

    private fun makeInsertionTask() {
        var mLastError: Exception? = null

        lifecycleScope.executeAsyncTask(
            onStart = {
                mProgress!!.show()
            },
            doInBackground = {
                try {
                    insertEventOnCalendar("Evento insertado",DateTime(System.currentTimeMillis()),DateTime(System.currentTimeMillis()+3600000))
                } catch (e: Exception) {
                    mLastError = e
                    lifecycleScope.cancel()
                    null
                }
            },
            onPostExecute = { output ->
                mProgress!!.hide()
                if (output == null) {
                    Log.d("Google", "Failed inserting event")
                } else {
                        binding.txtOut.text = (output.summary)
                        Log.d(
                            "Google",
                            output.id.toString() + " " + output.summary + " " + output.start
                        )
                    }
            },
            onCancelled = {
                mProgress!!.hide()
                if (mLastError != null) {
                    if (mLastError is GooglePlayServicesAvailabilityIOException) {
                        showGooglePlayServicesAvailabilityErrorDialog(
                            (mLastError as GooglePlayServicesAvailabilityIOException)
                                .connectionStatusCode
                        )
                    } else if (mLastError is UserRecoverableAuthIOException) {
                        this.startActivityForResult(
                            (mLastError as UserRecoverableAuthIOException).intent,
                            REQUEST_AUTHORIZATION
                        )
                    } else {
                        binding.txtOut.text = "The following error occurred:\n" + mLastError!!.message
                    }
                } else {
                    binding.txtOut.text = "Request cancelled."
                }
            }
        )
    }

    fun insertEventOnCalendar(eventSummary:String, startDate: DateTime, endDate:DateTime): Event? {
// Create a new calendar
        // Create a new calendar
//        val calendar: com.google.api.services.calendar.model.Calendar = com.google.api.services.calendar.model.Calendar()
//        calendar.summary = eventSummary
//        calendar.timeZone = "America/Los_Angeles"
////        calendar.set(Calendar.)
//
//// Insert the new calendar
//
//// Insert the new calendar
//        val createdCalendar: com.google.api.services.calendar.model.Calendar? = mService!!.calendars().insert(calendar).execute()
//
//        if (createdCalendar != null) {
//            System.out.println(createdCalendar.getId())
//        }
//
//        return (createdCalendar as EventModel)

// Using event
        var event = Event()
            .setSummary(eventSummary)
            .setLocation("800 Howard St., San Francisco, CA 94103")
            .setDescription("A chance to hear more about Google's developer products.")

//        val startDateTime = DateTime("2015-05-28T09:00:00-07:00")
        val start = EventDateTime()
            .setDateTime(startDate)
            .setTimeZone("America/Los_Angeles")
        event.setStart(start)

//        val endDateTime = DateTime("2015-05-28T17:00:00-07:00")
        val end = EventDateTime()
            .setDateTime(endDate)
            .setTimeZone("America/Los_Angeles")
        event.setEnd(end)

//        val recurrence = arrayOf("RRULE:FREQ=DAILY;COUNT=2")
//        event.setRecurrence(Arrays.asList(*recurrence))

        val attendees = arrayOf(
            EventAttendee().setEmail("daielt@example.com"),
            EventAttendee().setEmail("edmanuelt@example.com")
        )
        event.setAttendees(Arrays.asList(*attendees))

//        val reminderOverrides = arrayOf(
//            EventReminder().setMethod("email").setMinutes(24 * 60),
//            EventReminder().setMethod("popup").setMinutes(10)
//        )
//        val reminders: Event.Reminders = Reminders()
//            .setUseDefault(false)
//            .setOverrides(Arrays.asList(*reminderOverrides))
//        event.setReminders(reminders)

        val calendarId = "primary"
        event = mService!!.events().insert(calendarId, event).execute()
        System.out.printf("Event created: %s\n", event.getHtmlLink())
        return event
    }
}