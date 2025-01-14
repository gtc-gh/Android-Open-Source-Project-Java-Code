/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.http;

import android.os.Bundle;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Vector;

import java.security.cert.X509Certificate;

import com.android.org.bouncycastle.asn1.DERObjectIdentifier;
import com.android.org.bouncycastle.asn1.x509.X509Name;

/**
 * SSL certificate info (certificate details) class
 */
public class SslCertificate {

    /**
     * SimpleDateFormat pattern for an ISO 8601 date
     */
    private static String ISO_8601_DATE_FORMAT = "yyyy-MM-dd HH:mm:ssZ";

    /**
     * Name of the entity this certificate is issued to
     */
    private DName mIssuedTo;

    /**
     * Name of the entity this certificate is issued by
     */
    private DName mIssuedBy;

    /**
     * Not-before date from the validity period
     */
    private Date mValidNotBefore;

    /**
     * Not-after date from the validity period
     */
    private Date mValidNotAfter;

     /**
     * Bundle key names
     */
    private static final String ISSUED_TO = "issued-to";
    private static final String ISSUED_BY = "issued-by";
    private static final String VALID_NOT_BEFORE = "valid-not-before";
    private static final String VALID_NOT_AFTER = "valid-not-after";

    /**
     * Saves the certificate state to a bundle
     * @param certificate The SSL certificate to store
     * @return A bundle with the certificate stored in it or null if fails
     */
    public static Bundle saveState(SslCertificate certificate) {
        Bundle bundle = null;

        if (certificate != null) {
            bundle = new Bundle();

            bundle.putString(ISSUED_TO, certificate.getIssuedTo().getDName());
            bundle.putString(ISSUED_BY, certificate.getIssuedBy().getDName());

            bundle.putString(VALID_NOT_BEFORE, certificate.getValidNotBefore());
            bundle.putString(VALID_NOT_AFTER, certificate.getValidNotAfter());
        }

        return bundle;
    }

    /**
     * Restores the certificate stored in the bundle
     * @param bundle The bundle with the certificate state stored in it
     * @return The SSL certificate stored in the bundle or null if fails
     */
    public static SslCertificate restoreState(Bundle bundle) {
        if (bundle != null) {
            return new SslCertificate(
                bundle.getString(ISSUED_TO),
                bundle.getString(ISSUED_BY),
                bundle.getString(VALID_NOT_BEFORE),
                bundle.getString(VALID_NOT_AFTER));
        }

        return null;
    }

    /**
     * Creates a new SSL certificate object
     * @param issuedTo The entity this certificate is issued to
     * @param issuedBy The entity that issued this certificate
     * @param validNotBefore The not-before date from the certificate validity period in ISO 8601 format
     * @param validNotAfter The not-after date from the certificate validity period in ISO 8601 format
     * @deprecated Use {@link #SslCertificate(X509Certificate)}
     */
    @Deprecated
    public SslCertificate(
            String issuedTo, String issuedBy, String validNotBefore, String validNotAfter) {
        this(issuedTo, issuedBy, parseDate(validNotBefore), parseDate(validNotAfter));
    }

    /**
     * Creates a new SSL certificate object
     * @param issuedTo The entity this certificate is issued to
     * @param issuedBy The entity that issued this certificate
     * @param validNotBefore The not-before date from the certificate validity period
     * @param validNotAfter The not-after date from the certificate validity period
     * @deprecated Use {@link #SslCertificate(X509Certificate)}
     */
    @Deprecated
    public SslCertificate(
            String issuedTo, String issuedBy, Date validNotBefore, Date validNotAfter) {
        mIssuedTo = new DName(issuedTo);
        mIssuedBy = new DName(issuedBy);
        mValidNotBefore = cloneDate(validNotBefore);
        mValidNotAfter  = cloneDate(validNotAfter);
    }

    /**
     * Creates a new SSL certificate object from an X509 certificate
     * @param certificate X509 certificate
     */
    public SslCertificate(X509Certificate certificate) {
        this(certificate.getSubjectDN().getName(),
             certificate.getIssuerDN().getName(),
             certificate.getNotBefore(),
             certificate.getNotAfter());
    }

    /**
     * @return Not-before date from the certificate validity period or
     * "" if none has been set
     */
    public Date getValidNotBeforeDate() {
        return cloneDate(mValidNotBefore);
    }

    /**
     * @return Not-before date from the certificate validity period in
     * ISO 8601 format or "" if none has been set
     *
     * @deprecated Use {@link #getValidNotBeforeDate()}
     */
    @Deprecated
    public String getValidNotBefore() {
        return formatDate(mValidNotBefore);
    }

    /**
     * @return Not-after date from the certificate validity period or
     * "" if none has been set
     */
    public Date getValidNotAfterDate() {
        return cloneDate(mValidNotAfter);
    }

    /**
     * @return Not-after date from the certificate validity period in
     * ISO 8601 format or "" if none has been set
     *
     * @deprecated Use {@link #getValidNotAfterDate()}
     */
    @Deprecated
    public String getValidNotAfter() {
        return formatDate(mValidNotAfter);
    }

    /**
     * @return Issued-to distinguished name or null if none has been set
     */
    public DName getIssuedTo() {
        return mIssuedTo;
    }

    /**
     * @return Issued-by distinguished name or null if none has been set
     */
    public DName getIssuedBy() {
        return mIssuedBy;
    }

    /**
     * @return A string representation of this certificate for debugging
     */
    public String toString() {
        return
            "Issued to: " + mIssuedTo.getDName() + ";\n" +
            "Issued by: " + mIssuedBy.getDName() + ";\n";
    }

    /**
     * Parse an ISO 8601 date converting ParseExceptions to a null result;
     */
    private static Date parseDate(String string) {
        try {
            return new SimpleDateFormat(ISO_8601_DATE_FORMAT).parse(string);
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * Format a date as an ISO 8601 string, return "" for a null date
     */
    private static String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        return new SimpleDateFormat(ISO_8601_DATE_FORMAT).format(date);
    }

    /**
     * Clone a possibly null Date
     */
    private static Date cloneDate(Date date) {
        if (date == null) {
            return null;
        }
        return (Date) date.clone();
    }

    /**
     * A distinguished name helper class: a 3-tuple of:
     * - common name (CN),
     * - organization (O),
     * - organizational unit (OU)
     */
    public class DName {
        /**
         * Distinguished name (normally includes CN, O, and OU names)
         */
        private String mDName;

        /**
         * Common-name (CN) component of the name
         */
        private String mCName;

        /**
         * Organization (O) component of the name
         */
        private String mOName;

        /**
         * Organizational Unit (OU) component of the name
         */
        private String mUName;

        /**
         * Creates a new distinguished name
         * @param dName The distinguished name
         */
        public DName(String dName) {
            if (dName != null) {
                mDName = dName;
                try {
                    X509Name x509Name = new X509Name(dName);

                    Vector val = x509Name.getValues();
                    Vector oid = x509Name.getOIDs();

                    for (int i = 0; i < oid.size(); i++) {
                        if (oid.elementAt(i).equals(X509Name.CN)) {
                            mCName = (String) val.elementAt(i);
                            continue;
                        }

                        if (oid.elementAt(i).equals(X509Name.O)) {
                            mOName = (String) val.elementAt(i);
                            continue;
                        }

                        if (oid.elementAt(i).equals(X509Name.OU)) {
                            mUName = (String) val.elementAt(i);
                            continue;
                        }
                    }
                } catch (IllegalArgumentException ex) {
                    // thrown if there is an error parsing the string
                }
            }
        }

        /**
         * @return The distinguished name (normally includes CN, O, and OU names)
         */
        public String getDName() {
            return mDName != null ? mDName : "";
        }

        /**
         * @return The Common-name (CN) component of this name
         */
        public String getCName() {
            return mCName != null ? mCName : "";
        }

        /**
         * @return The Organization (O) component of this name
         */
        public String getOName() {
            return mOName != null ? mOName : "";
        }

        /**
         * @return The Organizational Unit (OU) component of this name
         */
        public String getUName() {
            return mUName != null ? mUName : "";
        }
    }
}
