package com.sai.drowsydriver;

import android.content.Context;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class Utils {
    private static String TAG = "Utils";

    public static void sms(Context context, String phoneNumber, String text) {
        Log.i(TAG, "sending SMS...");
        SmsManager smsManager = SmsManager.getDefault();
        String body = text;
        smsManager.sendTextMessage(
                "tel:" + phoneNumber,
                null,
                body + "\n" + GetLocation(context),
                null,
                null);
        Log.i(TAG, "sent SMS");
    }

    private static String GetLocation(Context context) {
        String location = "";
        String DefaultLocation = "3141 Red Fern Dr Olympia WA 98502";

        Log.i(TAG, "fetching location");
        LocationManager mLocationManager = (LocationManager) context
                .getSystemService(Context.LOCATION_SERVICE);
        if(mLocationManager == null)
            return DefaultLocation;

        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setPowerRequirement(Criteria.POWER_LOW);

        try {
            Geocoder mGC = new Geocoder(context, Locale.ENGLISH);
            String locationProvider = mLocationManager.getBestProvider(criteria, true);
            if(locationProvider == null)
                return DefaultLocation;
            Location mLocation = mLocationManager
                    .getLastKnownLocation(locationProvider);

            if(mLocation == null)
            {
                return DefaultLocation;
            }

            List<Address> addresses;
            double currentLong = mLocation.getLongitude();
            double currentLat = mLocation.getLatitude();
            location = "https://maps.google.com/maps?q=" + currentLat + ","  + currentLong + "&iwloc=A&hl=en"+"\n";
            addresses = mGC.getFromLocation(currentLat, currentLong, 1);
            if (addresses != null) {
                Address currentAddr = addresses.get(0);
                StringBuilder mSB = new StringBuilder("Address:\n");
                for (int i = 0; i < currentAddr.getMaxAddressLineIndex(); i++) {
                    mSB.append(currentAddr.getAddressLine(i)).append("\n");
                }

                location += (mSB.toString());
            }
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
        Log.i(TAG, "location acquired");
        return location;
    }

    public static void email(Context context, String email, String text) {

        Log.i(TAG, "sending email...");
        String toAddress = email;
        String subject = "From DrowsyDriver";
        String body = text;
        try {
            GMailSender sender = new GMailSender("drowsyDriverDoNotReply@gmail.com", "Saibaba1214");
            sender.sendMail(subject, body + "\n" + GetLocation(context), "drowsyDriverDoNotReply@gmail.com", toAddress);
        } catch (Exception e) {
            Log.e("SendMail", e.getMessage(), e);
        }
        Log.i(TAG, "sent email");
    }
}
