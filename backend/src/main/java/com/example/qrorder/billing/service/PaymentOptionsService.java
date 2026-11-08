package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.BillResponse;
import com.example.qrorder.billing.dto.CustomerPaymentOptions;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.dining.service.CustomerContext;
import org.springframework.stereotype.Service;

/** What a diner is offered at payment time. Built from the table's bill and the hotel's payment settings. */
@Service
public class PaymentOptionsService {

    private final BillService bills;
    private final PaymentSettingsService settings;

    public PaymentOptionsService(BillService bills, PaymentSettingsService settings) {
        this.bills = bills;
        this.settings = settings;
    }

    public CustomerPaymentOptions forCustomer(CustomerContext customer) {
        BillResponse bill = bills.forCustomer(customer);
        PaymentSettings s = settings.effective(customer.hotelId());

        boolean upi = s.accepts(PaymentMethod.UPI);
        long due = bill.totalMinor(); // from 10.4 on: what is still owed after payments
        String link = upi
                ? UpiLinks.build(s.getUpiId(), s.getPayeeName(), bill.currency(), due, "Table " + bill.tableNumber())
                : null;

        return new CustomerPaymentOptions(s.getEnabledMethods(), upi ? s.getUpiId() : null,
                upi ? s.getPayeeName() : null, link, s.isPartialPaymentsAllowed(), bill.currency(), due,
                bill.complete());
    }
}