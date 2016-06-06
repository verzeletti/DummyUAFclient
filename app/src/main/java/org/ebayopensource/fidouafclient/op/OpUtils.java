package org.ebayopensource.fidouafclient.op;


import android.util.Base64;

import com.google.gson.Gson;

import org.ebayopensource.fido.uaf.msg.TrustedFacets;
import org.ebayopensource.fido.uaf.msg.TrustedFacetsList;
import org.ebayopensource.fido.uaf.msg.Version;
import org.ebayopensource.fidouafclient.curl.Curl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Utility Class for UaFRequest messages - Registration & Authentication
 */
public abstract class OpUtils {


    public static String extractAppId(String serverResponse) {
        JSONArray requestArray = null;
        String appID = "";
        try {
            requestArray = new JSONArray(serverResponse);
            appID = ((JSONObject) requestArray.get(0)).getJSONObject("header").getString("appID");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return appID;
    }

    /**
     * Process Request Message
     *
     * @param serverResponse    Registration or Authentication request message
     * @param trustedFacetsJson trusted facets Id get from RP Server
     * @param appFacetId        Application facet Ids
     * @param isTrx             always false for Registration messages. For Authentication it should be true only for transactions
     * @return uafProtocolMessage
     */
    public static String getUafRequest(String serverResponse, String trustedFacetsJson, String appFacetId, boolean isTrx) {
        String msg = "{\"uafProtocolMessage\":\"";
        try {
            JSONArray requestArray = new JSONArray(serverResponse);
            String appID = ((JSONObject) requestArray.get(0)).getJSONObject("header").getString("appID");
            Version version = (new Gson()).fromJson(((JSONObject) requestArray.get(0)).getJSONObject("header").getString("upv"), Version.class);
            String facetId = "";
            if (!appFacetId.isEmpty()) {
                facetId = appFacetId.split(",")[0];
            }
            // If the AppID is null or empty, the client MUST set the AppID to be the FacetID of
            // the caller, and the operation may proceed without additional processing.
            if (appID == null || appID.isEmpty()) {
                if (!appFacetId.isEmpty()) {
                    ((JSONObject) requestArray.get(0)).getJSONObject("header").put("appID", facetId);
                }
            } else {
                //If the AppID is not an HTTPS URL, and matches the FacetID of the caller, no additional
                // processing is necessary and the operation may proceed.
                if (!isAppIdEqualsFacetId(appFacetId, appID)) {
                    TrustedFacetsList trustedFacets = (new Gson()).fromJson(trustedFacetsJson, TrustedFacetsList.class);
                    if (trustedFacets == null){
                        return getEmptyUafMsgRegRequest();
                    }
                    if (trustedFacets.getTrustedFacets() == null){
                        return getEmptyUafMsgRegRequest();
                    }
                    // After processing the trustedFacets entry of the correct version and removing
                    // any invalid entries, if the caller's FacetID matches one listed in ids,
                    // the operation is allowed.
                    boolean facetFound = processTrustedFacetsList(trustedFacets, version, appFacetId);

                    if (!facetFound) {
                        return getEmptyUafMsgRegRequest();
                    }
                }
            }
            if (isTrx) {
                ((JSONObject) requestArray.get(0)).put("transaction", getTransaction());
            }
            JSONObject uafMsg = new JSONObject();
            uafMsg.put("uafProtocolMessage", requestArray.toString());
            return uafMsg.toString();
        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return getEmptyUafMsgRegRequest();
    }

    private static boolean isAppIdEqualsFacetId(String appFacetIds, String appID) {
        String[] searchHelper = appFacetIds.split(",");
        for (String facet : searchHelper) {
            if (facet.equals(appID)) {
                return true;
            }
        }
        return false;
    }

    public static String getEmptyUafMsgRegRequest() {
        String msg = "{\"uafProtocolMessage\":";
        msg = msg + "\"\"";
        msg = msg + "}";
        return msg;
    }

    private static JSONArray getTransaction() {
        JSONArray ret = new JSONArray();
        JSONObject trx = new JSONObject();

        try {
            trx.put("contentType", "text/plain");
            trx.put("content", Base64.encodeToString("Authentication".getBytes(), Base64.URL_SAFE));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        ret.put(trx);
        return ret;
    }

    /**
     * From among the objects in the trustedFacet array, select the one with the version matching
     * that of the protocol message version. The scheme of URLs in ids MUST identify either an
     * application identity (e.g. using the apk:, ios: or similar scheme) or an https: Web Origin [RFC6454].
     * Entries in ids using the https:// scheme MUST contain only scheme, host and port components,
     * with an optional trailing /. Any path, query string, username/password, or fragment information
     * MUST be discarded.
     *
     * @param trustedFacetsList
     * @param version
     * @param appFacetId
     * @return true if appID list contains facetId (current Android application's signature).
     */
    private static boolean processTrustedFacetsList(TrustedFacetsList trustedFacetsList, Version version, String appFacetId) {
        for (TrustedFacets trustedFacets : trustedFacetsList.getTrustedFacets()) {
            // select the one with the version matching that of the protocol message version
            if ((trustedFacets.getVersion().minor >= version.minor)
                    && (trustedFacets.getVersion().major <= version.major)) {
                //The scheme of URLs in ids MUST identify either an application identity
                // (e.g. using the apk:, ios: or similar scheme) or an https: Web Origin [RFC6454].
                String[] searchHelper = appFacetId.split(",");
                for (String facetId : searchHelper) {
                    for (String id : trustedFacets.getIds()) {
                        if (id.equals(facetId)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Fetches the Trusted Facet List using the HTTP GET method. The location MUST be identified with
     * an HTTPS URL. A Trusted Facet List MAY contain an unlimited number of entries, but clients MAY
     * truncate or decline to process large responses.
     *
     * @param appID an identifier for a set of different Facets of a relying party's application.
     *              The AppID is a URL pointing to the TrustedFacets, i.e. list of FacetIDs related
     *              to this AppID.
     * @return Trusted Facets List
     */
    private static String getTrustedFacets(String appID) {
        //TODO The caching related HTTP header fields in the HTTP response (e.g. “Expires”) SHOULD be respected when fetching a Trusted Facets List.
        return Curl.getInSeparateThread(appID);
    }

    public static String clientSendRegResponse(String uafMessage, String endpoint) {
        StringBuffer res = new StringBuffer();
        String decoded = "";
        try {
            JSONObject json = new JSONObject(uafMessage);
            decoded = json.getString("uafProtocolMessage").replace("\\", "");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        res.append("#uafMessageegOut\n" + decoded);
        String headerStr = "Content-Type:Application/json Accept:Application/json";
        res.append("\n\n#ServerResponse\n");
        String serverResponse = Curl.postInSeparateThread(endpoint, headerStr, decoded);
        res.append(serverResponse);
        return res.toString();
    }


}
