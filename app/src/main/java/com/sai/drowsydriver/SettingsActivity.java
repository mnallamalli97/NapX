package com.sai.drowsydriver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;


public class SettingsActivity extends Activity {
    SharedPreferences mPref;
    EditText mPhoneEdit;
    EditText mEmailEdit;
    CheckBox mUseAccelCheckBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mPref = getApplicationContext().getSharedPreferences(MainActivity.PREF, 0);

        mPhoneEdit = ((EditText) findViewById(R.id.phoneEdit));
        mPhoneEdit.setText(mPref.getString(MainActivity.KEY_PHONE, ""));

        mEmailEdit = ((EditText) findViewById(R.id.emailEdit));
        mEmailEdit.setText(mPref.getString(MainActivity.KEY_EMAIL, ""));

        mUseAccelCheckBox = ((CheckBox)findViewById(R.id.useAcceleration));
        mUseAccelCheckBox.setChecked(mPref.getBoolean(MainActivity.KEY_ACCEL, true));

        findViewById(R.id.btnSaveSettings).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                SharedPreferences.Editor editor = mPref.edit();

                editor.putString(MainActivity.KEY_PHONE, mPhoneEdit.getText().toString());
                editor.putString(MainActivity.KEY_EMAIL, mEmailEdit.getText().toString());
                editor.putBoolean(MainActivity.KEY_ACCEL, mUseAccelCheckBox.isChecked());
                editor.commit();
                finish();
            }
        });
    }

}
