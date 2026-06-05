package com.wallet.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Launcher screen: unlock (or create) the vault with a passphrase, then list the
 * saved items and open the browser. The passphrase never leaves the device and is
 * never stored — only the PBKDF2 salt is.
 */
public final class VaultActivity extends Activity {

    private VaultStore vault;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        vault = ((App) getApplication()).vault();
        showUnlock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vault.isUnlocked()) showVault();
    }

    private void showUnlock() {
        LinearLayout root = column();

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);

        final EditText pass = new EditText(this);
        pass.setHint(R.string.passphrase);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Button unlock = new Button(this);
        unlock.setText(vault.isInitialised() ? R.string.unlock : R.string.create_vault);
        unlock.setOnClickListener(v -> {
            try {
                vault.unlock(pass.getText().toString().toCharArray());
                showVault();
            } catch (Exception e) {
                toast("Unlock failed: " + e.getMessage());
            }
        });

        root.addView(title);
        root.addView(pass);
        root.addView(unlock);
        setContentView(root);
    }

    private void showVault() {
        LinearLayout root = column();

        Button browse = new Button(this);
        browse.setText(R.string.open_browser);
        browse.setOnClickListener(v -> startActivity(new Intent(this, BrowserActivity.class)));

        TextView header = new TextView(this);
        header.setText(R.string.saved_items);
        header.setTextSize(20);

        ListView list = new ListView(this);
        List<String> items = vault.list();
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));

        root.addView(browse);
        root.addView(header);
        root.addView(list);
        setContentView(root);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);
        l.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return l;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
