package pivx.org.pivxwallet.ui.transaction_send_activity;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.uri.PivxURI;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import pivx.org.pivxwallet.R;
import pivx.org.pivxwallet.contacts.Contact;
import pivx.org.pivxwallet.module.ContactAlreadyExistException;
import pivx.org.pivxwallet.rate.db.PivxRate;
import pivx.org.pivxwallet.service.PivxWalletService;
import pivx.org.pivxwallet.ui.base.BaseActivity;
import pivx.org.pivxwallet.ui.base.dialogs.SimpleTextDialog;
import pivx.org.pivxwallet.ui.base.dialogs.SimpleTwoButtonsDialog;
import pivx.org.pivxwallet.utils.DialogBuilder;
import pivx.org.pivxwallet.utils.DialogsUtil;
import pivx.org.pivxwallet.utils.scanner.ScanActivity;

import static android.Manifest.permission_group.CAMERA;
import static pivx.org.pivxwallet.service.IntentsConstants.ACTION_BROADCAST_TRANSACTION;
import static pivx.org.pivxwallet.service.IntentsConstants.DATA_TRANSACTION_HASH;
import static pivx.org.pivxwallet.utils.scanner.ScanActivity.INTENT_EXTRA_RESULT;

/**
 * Created by Neoperol on 5/4/17.
 */

public class SendActivity extends BaseActivity implements View.OnClickListener {
    private static final int SCANNER_RESULT = 122;
    private Button buttonSend;
    private AutoCompleteTextView edit_address;
    private TextView txt_local_currency;
    private EditText edit_amount;
    private EditText edit_memo;
    private MyFilterableAdapter filterableAdapter;
    private String addressStr;
    private PivxRate pivxRate;
    private SimpleTextDialog errorDialog;

    @Override
    protected void onCreateView(Bundle savedInstanceState,ViewGroup container) {
        getLayoutInflater().inflate(R.layout.fragment_transaction_send, container);
        setTitle("Send");
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        edit_address = (AutoCompleteTextView) findViewById(R.id.edit_address);
        edit_amount = (EditText) findViewById(R.id.edit_amount);
        edit_memo = (EditText) findViewById(R.id.edit_memo);
        txt_local_currency = (TextView) findViewById(R.id.txt_local_currency);
        findViewById(R.id.button_qr).setOnClickListener(this);
        buttonSend = (Button) findViewById(R.id.btnSend);
        buttonSend.setOnClickListener(this);

        edit_amount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length()>0) {
                    Coin coin = Coin.parseCoin(s.toString());
                    if (pivxRate == null)
                        pivxRate = pivxModule.getRate(pivxApplication.getAppConf().getSelectedRateCoin());
                    txt_local_currency.setText(
                            pivxApplication.getCentralFormats().getNumberFormat().format(
                                    new BigDecimal(coin.getValue() * pivxRate.getValue().doubleValue()).movePointLeft(8)
                            )
                                    + " "+pivxRate.getCoin()
                    );
                }

            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // todo: This is not updating the filter..
        if (filterableAdapter==null) {
            List<Contact> list = new ArrayList<>(pivxModule.getContacts());
            filterableAdapter = new MyFilterableAdapter(this,list );
            edit_address.setAdapter(filterableAdapter);
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnSend){
            try {
                if (checkConnectivity()){
                    send();
                }
            }catch (IllegalArgumentException e){
                e.printStackTrace();
                showErrorDialog(e.getMessage());
            }
        }else if (id == R.id.button_qr){
            if (!checkPermission(CAMERA)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int permsRequestCode = 200;
                    String[] perms = {"android.permission.CAMERA"};
                    requestPermissions(perms, permsRequestCode);
                }
            }
            startActivityForResult(new Intent(this, ScanActivity.class),SCANNER_RESULT);
        }
    }

    private boolean checkConnectivity() {
        if (!isOnline()){
            SimpleTwoButtonsDialog noConnectivityDialog = DialogsUtil.buildSimpleTwoBtnsDialog(
                    this,
                    getString(R.string.error_no_connectivity_title),
                    getString(R.string.error_no_connectivity_body),
                    new SimpleTwoButtonsDialog.SimpleTwoBtnsDialogListener() {
                        @Override
                        public void onRightBtnClicked(SimpleTwoButtonsDialog dialog) {
                            send();
                            dialog.dismiss();
                        }

                        @Override
                        public void onLeftBtnClicked(SimpleTwoButtonsDialog dialog) {
                            dialog.dismiss();
                        }
                    }
            );
            noConnectivityDialog.setLeftBtnTextColor(Color.WHITE)
                    .setLeftBtnBackgroundColor(getColor(R.color.lightGreen))
                    .setRightBtnTextColor(Color.BLACK)
                    .setRightBtnBackgroundColor(Color.WHITE)
                    .setLeftBtnText(getString(R.string.button_cancel))
                    .setRightBtnText(getString(R.string.button_ok))
                    .show();

            return false;
        }
        return true;
    }

    public boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SCANNER_RESULT){
            if (resultCode==RESULT_OK) {
                try {
                    String address = data.getStringExtra(INTENT_EXTRA_RESULT);
                    PivxURI pivxUri = new PivxURI(address);
                    final String tempPubKey = pivxUri.getAddress().toBase58();
                    edit_address.setText(tempPubKey);
                }catch (Exception e){
                    Toast.makeText(this,"Bad address",Toast.LENGTH_LONG).show();
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private boolean checkPermission(String permission) {
        int result = ContextCompat.checkSelfPermission(getApplicationContext(),permission);
        return result == PackageManager.PERMISSION_GRANTED;
    }

    private void showErrorDialog(String message) {
        if (errorDialog==null){
            errorDialog = DialogsUtil.buildSimpleErrorTextDialog(this,getResources().getString(R.string.invalid_inputs),message);
        }else {
            errorDialog.setBody(message);
        }
        errorDialog.show(getFragmentManager(),getResources().getString(R.string.send_error_dialog_tag));
    }

    private void send() {
        try {
            // create the tx
            addressStr = edit_address.getText().toString();
            if (!pivxModule.chechAddress(addressStr))
                throw new IllegalArgumentException("Address not valid");
            String amountStr = edit_amount.getText().toString();
            if (amountStr.length() < 1) throw new IllegalArgumentException("Amount not valid");
            Coin amount = Coin.parseCoin(amountStr);
            if (amount.isGreaterThan(Coin.valueOf(pivxModule.getAvailableBalance())))
                throw new IllegalArgumentException("Insuficient balance");
            String memo = edit_memo.getText().toString();
            // build a tx with the default fee
            Transaction transaction = pivxModule.buildSendTx(addressStr, amount, memo);
            // dialog
            launchDialog(transaction);

        } catch (InsufficientMoneyException e) {
            e.printStackTrace();
            throw new IllegalArgumentException("Insuficient balance");
        }
    }

    private void launchDialog(final Transaction transaction){
        LayoutInflater content = LayoutInflater.from(SendActivity.this);
        View dialogView = content.inflate(R.layout.dialog_send_confirmation, null);

        DialogBuilder sendDialog = new DialogBuilder(SendActivity.this);
        sendDialog.setTitle("Transaction Information");
        sendDialog.setView(dialogView);
        TextView txtAmount = (TextView) dialogView.findViewById(R.id.txt_amount);
        TextView txt_local_currency = (TextView) dialogView.findViewById(R.id.txt_local_currency);
        TextView txt_fee = (TextView) dialogView.findViewById(R.id.txt_fee);
        TextView txt_memo = (TextView) dialogView.findViewById(R.id.txt_memo);
        final EditText edit_receiver = (EditText) dialogView.findViewById(R.id.edit_receiver);

        // init
        Coin value = pivxModule.getValueSentFromMe(transaction,true);
        txtAmount.setText(value.toFriendlyString());
        if (pivxRate == null)
            pivxRate = pivxModule.getRate(pivxApplication.getAppConf().getSelectedRateCoin());
        txt_local_currency.setText(
                pivxApplication.getCentralFormats().getNumberFormat().format(
                        new BigDecimal(value.getValue() * pivxRate.getValue().doubleValue()).movePointLeft(8)
                )
                        + " "+pivxRate.getCoin()
        );
        txt_fee.setText(transaction.getFee().toFriendlyString());
        txt_memo.setText(transaction.getMemo()!=null?transaction.getMemo():"No description");
        sendDialog.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String contactName = edit_receiver.getText().toString();
                if (contactName.length()>0){
                    Contact contact = new Contact(contactName);
                    contact.addAddress(addressStr);
                    contact.addTx(transaction.getHash());
                    try {
                        pivxModule.saveContact(contact);
                    } catch (ContactAlreadyExistException e) {
                        e.printStackTrace();
                        Toast.makeText(SendActivity.this,R.string.contact_already_exist,Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                pivxModule.commitTx(transaction);
                Intent intent = new Intent(SendActivity.this, PivxWalletService.class);
                intent.setAction(ACTION_BROADCAST_TRANSACTION);
                intent.putExtra(DATA_TRANSACTION_HASH,transaction.getHash().getBytes());
                startService(intent);
                Toast.makeText(SendActivity.this,R.string.sending_tx,Toast.LENGTH_LONG).show();
                onBackPressed();
                dialog.dismiss();
            }
        });
        sendDialog.show();
    }

}
