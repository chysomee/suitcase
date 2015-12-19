package model;

import net.AttachmentManager;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.*;

import static org.opendatakit.wink.client.WinkClient.*;

//!!!ATTENTION!!! One per table
public class ODKCSV implements Iterable<String[]> {
    public class ODKCSVIterator implements Iterator<String[]> {
        private int cursor;
        private boolean scanFormatting;
        private boolean localLink;

        public ODKCSVIterator() {
            this.cursor = 0;
            this.scanFormatting = false;
            this.localLink = false;
        }

        @Override
        public boolean hasNext() {
            return this.cursor < size;
        }

        @Override
        public String[] next() {
            return next(scanFormatting, localLink);
        }

        public String[] next(boolean scanFormatting, boolean localLink) {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            String[] nextLine = null;

            try {
                nextLine = get(cursor++, scanFormatting, localLink);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return nextLine;
        }

        public void setScanFormatting(boolean set) {
            this.scanFormatting = set;
        }

        public void setLocalLink(boolean set) {
            this.localLink = set;
        }
    }

    //extra columns to add
//    private static final String[] ROW_METADATA = new String[] {
//            rowDefId,
//            rowDefFormId,
//            rowDefLocale,
//            rowDefSavepointType,
//            rowDefSavepointTimestamp,
//            rowDefSavepointCreator,
//            rowDefRowETag,
//            rowDefFilterType,
//            rowDefFilterValue
//    };

    private enum ACTION {
        KEEP,       //No modification
        FILTER,     //Remove
        LINK,       //Convert to a hyperlink
        SCAN_RAW    //Insert Scan's raw output
    }

    //Maps POSITION to metadata
    private enum POSITION {FRONT, END}
    private static final Map<POSITION, List<String>> METADATA_POSITION;
    static {
        METADATA_POSITION = new HashMap<POSITION, List<String>>();
        METADATA_POSITION.put(POSITION.FRONT, new ArrayList<String>());
        METADATA_POSITION.put(POSITION.END, new ArrayList<String>());

        METADATA_POSITION.get(POSITION.FRONT).add(rowDefId);
        METADATA_POSITION.get(POSITION.FRONT).add(rowDefFormId);
        METADATA_POSITION.get(POSITION.FRONT).add(rowDefLocale);
        METADATA_POSITION.get(POSITION.FRONT).add(rowDefSavepointType);
        METADATA_POSITION.get(POSITION.FRONT).add(rowDefSavepointTimestamp);
        METADATA_POSITION.get(POSITION.FRONT).add(rowDefSavepointCreator);

        METADATA_POSITION.get(POSITION.END).add(rowDefRowETag);
        METADATA_POSITION.get(POSITION.END).add(rowDefFilterType);
        METADATA_POSITION.get(POSITION.END).add(rowDefFilterValue);
    }

    //Maps metadata to it's json identifier
    private static final Map<String, String> METADATA_JSON_NAME;
    static {
        METADATA_JSON_NAME = new HashMap<String, String>();

        METADATA_JSON_NAME.put(rowDefId, jsonId);
        METADATA_JSON_NAME.put(rowDefFormId, jsonFormId);
        METADATA_JSON_NAME.put(rowDefLocale, jsonLocale);
        METADATA_JSON_NAME.put(rowDefSavepointType, jsonSavepointType);
        METADATA_JSON_NAME.put(rowDefSavepointTimestamp, jsonSavepointTimestamp);
        METADATA_JSON_NAME.put(rowDefSavepointCreator, jsonSavepointCreator);
        METADATA_JSON_NAME.put(rowDefRowETag, jsonRowETag);
        METADATA_JSON_NAME.put(rowDefFilterType, jsonFilterScope + ": type");
        METADATA_JSON_NAME.put(rowDefFilterValue, jsonFilterScope + ": value");
    }

    private static final String NULL = "null";
    private static final String CONTENT_TYPE_ELEMENT_NAME = "contentType";
    private static final String URI_FRAG_ELEMENT_NAME = "uriFragment";
    private static final String SCAN_RAW_PREFIX = "raw_";
    //Due to optimization and how Scan is designed, 1 column is always filtered
    private static final int NUM_FILTERED = 1;

//    private JSONArray jsonRows;
    private List<JSONArray> jsonRows;
    private String[] completeCSVHeader;
    private String[] completeDataHeader;
    private int size;
    private Map<String, ACTION> colAction;
    private AttachmentManager attMngr;
    private AggregateTableInfo table;

    public ODKCSV(AttachmentManager attMngr, AggregateTableInfo table) {
        this.size = 0;
        this.attMngr = attMngr;
        this.jsonRows = new ArrayList<JSONArray>();
        this.table = table;
    }

    public ODKCSV(JSONArray rows, AttachmentManager attMngr, AggregateTableInfo table) throws JSONException {
        if (rows == null) {
            throw new IllegalArgumentException("invalid json");
        }

//        JSONArray allRows = rows.getJSONArray(jsonRowsString);

        this.size = rows.size();

        this.jsonRows = new ArrayList<JSONArray>();
        this.jsonRows.add(rows);

        this.completeDataHeader = extractHeader(rows.getJSONObject(0));
        this.completeCSVHeader = buildCSVHeader();
        this.colAction = buildActionMap();
        this.attMngr = attMngr;
        this.table = table;
    }

    public String[] getHeader(boolean scanFormatting) {
        if (this.size < 1) {
            throw new IllegalStateException();
        }

        if (!scanFormatting) {
            return this.completeCSVHeader;
        }

//        List<String> header = new ArrayList<String>();
//        ListIterator<String> headerIt = header.listIterator();
        String[] header = new String[this.completeCSVHeader.length - NUM_FILTERED];
        int offset = 0;
        for (int i = 0; i < this.completeCSVHeader.length; i++) {
            String col = this.completeCSVHeader[i];
            ACTION act = this.colAction.get(col);
            switch (act) {
                case KEEP:
                case LINK:
                    //KEEP and LINK both do no change to header
//                    header.add(col);
                    header[i - offset] = this.completeCSVHeader[i];
                    break;
                case SCAN_RAW:
//                    header.add(SCAN_RAW_PREFIX + header.get(headerIt.previousIndex()));
                    header[i - offset] = SCAN_RAW_PREFIX + header[i - offset - 1];
                    break;
                case FILTER:
                    //remove col from list of header
                    offset++;
                    break;
            }
        }

        return header;
    }

    public boolean tryAdd(JSONArray rows) throws JSONException {
        if (!isCompatible(rows)) {
            return false;
        }

        if (this.size < 1) {
            this.completeDataHeader = extractHeader(rows.getJSONObject(0));
            this.completeCSVHeader = buildCSVHeader();
            this.colAction = buildActionMap();
        }

        this.jsonRows.add(rows);
        this.size += rows.size();
        return true;
    }

    public String[] get(int rowIndex, boolean scanFormatting, boolean localLink) throws Exception {
        if (rowIndex >= size) {
            throw new NoSuchElementException();
        }

        int listIndex = 0;
        while (rowIndex >= this.jsonRows.get(listIndex).size()) {
            rowIndex -= this.jsonRows.get(listIndex).size();
            listIndex++;
        }

        JSONObject row = this.jsonRows.get(listIndex).getJSONObject(rowIndex);
        String[] front = getMetadata(row, POSITION.FRONT);
        String[] middle = getData(row, scanFormatting, localLink);
        String[] end = getMetadata(row, POSITION.END);

        //TODO: optimize to avoid copying arrays
        String[] sum = new String[front.length + middle.length + end.length];
        System.arraycopy(front, 0, sum, 0, front.length);
        System.arraycopy(middle, 0, sum, front.length, middle.length);
        System.arraycopy(end, 0, sum, front.length + middle.length, end.length);

        return sum;
    }

    private String[] extractHeader(JSONObject oneRow) throws JSONException {
        JSONArray orderedColumns = oneRow.getJSONArray(orderedColumnsDef);
        String[] columns = new String[orderedColumns.size()];

        for (int i = 0; i < columns.length; i++) {
            columns[i] = orderedColumns.getJSONObject(i).getString("column");
        }
        
        return columns;
    }
    
    private String[] buildCSVHeader() throws JSONException {
        List<String> header = new ArrayList<String>();
        header.addAll(METADATA_POSITION.get(POSITION.FRONT));
        for (String s : completeDataHeader) {
            header.add(s);
        }
        header.addAll(METADATA_POSITION.get(POSITION.END));

        String[] headerArr = new String[header.size()];
        headerArr = header.toArray(headerArr);
        return headerArr;
    }

    private boolean isCompatible(JSONArray rows) throws JSONException {
        if (this.size < 1) {
            //this instance is empty -> always compatible
            return true;
        }

        String[] newDataHeader = extractHeader(rows.getJSONObject(0));

        return newDataHeader.length == this.completeDataHeader.length &&
                Arrays.deepEquals(newDataHeader, this.completeDataHeader);

    }

    private String[] getMetadata(JSONObject row, POSITION pos) throws JSONException {
        List<String> metadataList = METADATA_POSITION.get(pos);
//        JSONObject row = this.jsonRows.get(listIndex).getJSONObject(rowIndex);

        String[] metadata = new String[metadataList.size()];

        for (int i = 0; i < metadata.length; i++) {
            String jsonName = METADATA_JSON_NAME.get(metadataList.get(i));

            if (jsonName.startsWith(jsonFilterScope)) {
                metadata[i] = row.getJSONObject(jsonFilterScope).optString(jsonName.split(":")[1].trim(), NULL);
            } else {
                metadata[i] = row.optString(jsonName, NULL);
            }
        }

        return metadata;
    }

    private String[] getData(JSONObject row, boolean scanFormatting, boolean localLink) throws Exception {
        //TODO:filter, handle case when scanFormatting = 1 & localLink = 0
        String rowId = row.optString(jsonId);

        ScanJson scanRaw = null;
        if (scanFormatting || localLink) {
            this.attMngr.getListOfRowAttachments(rowId);
            this.attMngr.downloadAttachments(rowId, !localLink);
            if (scanFormatting) {
                scanRaw = new ScanJson(this.attMngr.getScanRawJsonStream(rowId));
            }
        }

        int dataLength = this.completeDataHeader.length;
        if (scanFormatting) dataLength -= NUM_FILTERED;
        String[] data = new String[dataLength];

        JSONArray columns = row.getJSONArray(orderedColumnsDef);
        int offset = 0;
        for (int i = 0; i < this.completeDataHeader.length; i++) {
            String colName = this.completeDataHeader[i];
            ACTION act = this.colAction.get(colName);
            String value = columns.getJSONObject(i).optString("value", NULL);

            switch (act) {
                case KEEP:
                    data[i - offset] = value;
                    break;
                case FILTER:
                    if (!scanFormatting) {
                        data[i - offset] = value;
                    } else {
                        offset++;
                        //value is ignored when scanFormatting is high
                    }
                    break;
                case LINK:
                    data[i - offset] = makeLink(value, row, localLink);
                    break;
                case SCAN_RAW:
                    if (scanFormatting) {
                        value = scanRaw.getValue(this.completeDataHeader[i - 1]);
                    }
                    data[i - offset] = value;
                    break;
            }
        }

        return data;
    }

    private String makeLink(String fileName, JSONObject row, boolean localLink) throws IOException {
        String template = "=HYPERLINK(\"%s\", \"Ctrl + Click to view\")";
        String attachmentURL;
        if (localLink) {
            attachmentURL = this.attMngr.getAttachmentUrl(row.optString(jsonId), fileName, localLink).toString();
            if (attachmentURL == null) {
                return "null";
            }
        } else {
            attachmentURL = this.table.getServerUrl() + "/" + "tables" + "/" + this.table.getAppId() + "/" +
                    this.table.getTableId() + "/ref/" + this.table.getSchemaETag() + "/attachments/" +
                    row.optString(jsonId) + "/file/" + fileName;
        }

        return String.format(
                template,
                attachmentURL
        );
    }

    private Map<String, ACTION> buildActionMap() {
        Map<String, ACTION> actionMap = new HashMap<String, ACTION>();

        for (String s : this.completeCSVHeader) {
            if (s.endsWith(CONTENT_TYPE_ELEMENT_NAME)) {
                if (s.equals("raw_contentType")) {
                    actionMap.put(s, ACTION.FILTER); //TODO: handle this better
                } else {
                    actionMap.put(s, ACTION.SCAN_RAW);
                }
            } else if (s.endsWith(URI_FRAG_ELEMENT_NAME)) {
                actionMap.put(s, ACTION.LINK);
            } else {
                actionMap.put(s, ACTION.KEEP);
            }
        }

        return actionMap;
    }

    @Override
    public Iterator<String[]> iterator() {
        return getODKCSVIterator();
    }

    public ODKCSVIterator getODKCSVIterator() {
        return new ODKCSVIterator();
    }
}