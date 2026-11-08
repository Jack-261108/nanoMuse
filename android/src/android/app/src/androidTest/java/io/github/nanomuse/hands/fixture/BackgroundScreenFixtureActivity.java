package io.github.nanomuse.hands.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local-only target in the test APK, whose own process has no Kotlin runtime. */
public class BackgroundScreenFixtureActivity extends Activity {
    private int clicks;
    private int enters;
    private TextView status;
    private EditText field;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 80, 32, 32);
        layout.setBackgroundColor(Color.rgb(230, 240, 250));

        status = new TextView(this);
        status.setContentDescription("fixture-state");
        status.setTextSize(22f);
        status.setTextColor(Color.BLACK);

        field = new EditText(this);
        field.setContentDescription("fixture-input");
        field.setHint("Local test input");
        field.setSingleLine(true);
        field.setShowSoftInputOnFocus(false);
        field.setTextSize(22f);
        field.setTextColor(Color.BLACK);
        if (savedInstanceState != null) {
            clicks = savedInstanceState.getInt("clicks");
            enters = savedInstanceState.getInt("enters");
            field.setText(savedInstanceState.getString("input", ""));
        }

        Button click = new Button(this);
        click.setContentDescription("fixture-click");
        click.setText("Count one tap");
        click.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clicks++;
                update();
            }
        });
        field.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int key, KeyEvent event) {
                if (key != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    enters++;
                    update();
                }
                return true;
            }
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                update();
            }

            @Override
            public void afterTextChanged(Editable text) {}
        });

        TextView title = new TextView(this);
        title.setText("nanoMuse background screen validation");
        title.setTextSize(24f);
        title.setTextColor(Color.BLACK);
        layout.addView(title);
        layout.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
        layout.addView(click, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
        layout.addView(status);
        setContentView(layout);
        update();
        field.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("clicks", clicks);
        outState.putInt("enters", enters);
        outState.putString("input", field.getText().toString());
        super.onSaveInstanceState(outState);
    }

    private void update() {
        status.setText("clicks=" + clicks + ";enter=" + enters + ";value=" + field.getText());
    }
}
