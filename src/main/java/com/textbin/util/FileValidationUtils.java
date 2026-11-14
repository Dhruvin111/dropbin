package com.textbin.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Upload validation helpers (requires Java 11+).
 *
 * NOTE: signature checks are only ONE layer of defence. Also:
 *  - store files under random server-generated names, outside the web root
 *  - serve with "X-Content-Type-Options: nosniff" and a Content-Type from resolveSafeContentType()
 *  - serve SVG as attachment or with "Content-Security-Policy: sandbox" (ideally from a separate domain)
 *  - re-encode raster images (ImageIO) and virus-scan documents if the risk warrants it
 */
public final class FileValidationUtils {

    private static final Logger log = LoggerFactory.getLogger(FileValidationUtils.class);

    private static final int HEADER_BYTES = 16;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;   // 10 MB, adjust to your needs
    private static final int MAX_SVG_BYTES = 1024 * 1024;          // 1 MB
    private static final int MAX_SVG_DEPTH = 100;
    private static final int MAX_ZIP_ENTRIES = 1000;

    private static final Map<String, String> EXTENSION_TO_MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    );

    private static final String SVG_NS = "http://www.w3.org/2000/svg";

    /** Elements that can run script, load external content, or mutate attributes at runtime. */
    private static final Set<String> SVG_FORBIDDEN_ELEMENTS = Set.of(
            "script", "foreignobject", "iframe", "object", "embed", "applet",
            "set", "animate", "animatemotion", "animatetransform", "handler", "listener"
    );

    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*['\"]?\\s*([^)'\"]*)");

    private FileValidationUtils() {
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Preferred entry point: reads the header from the file itself, so the bytes
     * checked always belong to the upload being validated.
     */
    public static boolean isValidFileSignature(String filename, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_SIZE) {
            return false;
        }
        String ext = extensionOf(filename);
        if (ext == null || !EXTENSION_TO_MIME.containsKey(ext)) {
            return false;
        }

        try {
            switch (ext) {
                case "svg":
                    return isValidSvg(file);
                case "docx":
                    return hasZipMagic(readHeader(file)) && isValidDocx(file);
                default:
                    return matchesHeader(ext, readHeader(file));
            }
        } catch (Exception e) {
            log.debug("File validation failed for extension {}", ext, e);
            return false;
        }
    }

    /**
     * @deprecated the {@code header} argument is ignored; the header is now read from {@code file}
     * so that the two can never disagree. Use {@link #isValidFileSignature(String, MultipartFile)}.
     */
    @Deprecated
    public static boolean isValidFileSignature(String filename, byte[] header, MultipartFile file) {
        return isValidFileSignature(filename, file);
    }

    public static String determineCategory(String filename) {
        String ext = extensionOf(filename);
        if (ext == null || !EXTENSION_TO_MIME.containsKey(ext)) return null;

        if ("pdf".equals(ext)) return "PDF";
        if ("doc".equals(ext) || "docx".equals(ext)) return "WORD";
        return "IMAGE";
    }

    public static String determineCategory(String filename, String contentType) {
        // client-supplied content type is intentionally ignored
        return determineCategory(filename);
    }

    public static String resolveSafeContentType(String filename) {
        String ext = extensionOf(filename);
        if (ext == null) return "application/octet-stream";
        return EXTENSION_TO_MIME.getOrDefault(ext, "application/octet-stream");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Returns the lower-cased extension, or null if the filename is missing/suspicious. */
    private static String extensionOf(String filename) {
        if (filename == null || filename.isEmpty()) return null;
        if (filename.indexOf('\0') >= 0 || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase();
    }

    private static byte[] readHeader(MultipartFile file) throws java.io.IOException {
        try (InputStream is = file.getInputStream()) {
            return is.readNBytes(HEADER_BYTES);
        }
    }

    private static boolean matchesHeader(String ext, byte[] h) {
        if (h == null || h.length < 2) return false;

        switch (ext) {
            case "png":
                // 89 50 4E 47 0D 0A 1A 0A
                return h.length >= 8
                        && h[0] == (byte) 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47
                        && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A;

            case "jpg":
            case "jpeg":
                // FF D8 FF
                return h.length >= 3
                        && h[0] == (byte) 0xFF && h[1] == (byte) 0xD8 && h[2] == (byte) 0xFF;

            case "gif":
                // GIF87a or GIF89a
                return h.length >= 6
                        && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8'
                        && (h[4] == '7' || h[4] == '9') && h[5] == 'a';

            case "webp":
                // "RIFF" <4 bytes size> "WEBP"
                return h.length >= 12
                        && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                        && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';

            case "bmp":
                // "BM"
                return h[0] == 0x42 && h[1] == 0x4D;

            case "pdf":
                // "%PDF" (min 4 bytes; typically "%PDF-1.x")
                return h.length >= 4
                        && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F'
                        && (h.length < 5 || h[4] == '-' || Character.isWhitespace(h[4]));

            case "doc":
                // OLE2 compound file: D0 CF 11 E0 A1 B1 1A E1
                // (also used by XLS/PPT/MSI; use Apache POI if you must confirm it is Word)
                return h.length >= 8
                        && h[0] == (byte) 0xD0 && h[1] == (byte) 0xCF && h[2] == (byte) 0x11 && h[3] == (byte) 0xE0
                        && h[4] == (byte) 0xA1 && h[5] == (byte) 0xB1 && h[6] == (byte) 0x1A && h[7] == (byte) 0xE1;

            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    // DOCX
    // ------------------------------------------------------------------

    /** Standard local-file-header signature: PK 03 04 (empty/spanned archives are not valid uploads). */
    private static boolean hasZipMagic(byte[] h) {
        return h != null && h.length >= 4
                && h[0] == 0x50 && h[1] == 0x4B && h[2] == 0x03 && h[3] == 0x04;
    }

    /** A real .docx is a ZIP containing [Content_Types].xml and word/document.xml. */
    private static boolean isValidDocx(MultipartFile file) {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            boolean hasContentTypes = false;
            boolean hasDocument = false;
            int count = 0;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ZIP_ENTRIES) {
                    return false;
                }
                String name = entry.getName().replace('\\', '/');
                if ("[Content_Types].xml".equalsIgnoreCase(name)) {
                    hasContentTypes = true;
                } else if ("word/document.xml".equalsIgnoreCase(name)) {
                    hasDocument = true;
                }
                if (hasContentTypes && hasDocument) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // SVG
    // ------------------------------------------------------------------

    private static boolean isValidSvg(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            // read one byte more than the limit so oversize files are rejected, not truncated
            byte[] bytes = is.readNBytes(MAX_SVG_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_SVG_BYTES) {
                return false;
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // blocks XXE
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
            Element root = doc.getDocumentElement();
            if (root == null
                    || !"svg".equalsIgnoreCase(localName(root))
                    || !SVG_NS.equals(root.getNamespaceURI())) {
                return false;
            }

            if (!isSafeNode(root, 0)) {
                log.warn("SVG file rejected: unsafe element or attribute detected");
                return false;
            }
            return true;
        } catch (Exception e) {
            // malformed XML, DOCTYPE present, parser error, etc.
            return false;
        }
    }

    private static boolean isSafeNode(Node node, int depth) {
        if (depth > MAX_SVG_DEPTH) {
            return false;
        }

        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE:
                Element el = (Element) node;
                if (SVG_FORBIDDEN_ELEMENTS.contains(localName(el).toLowerCase())) {
                    return false;
                }
                if (!areAttributesSafe(el)) {
                    return false;
                }
                if ("style".equalsIgnoreCase(localName(el)) && !isSafeCss(el.getTextContent())) {
                    return false;
                }
                break;
            case Node.PROCESSING_INSTRUCTION_NODE:   // e.g. <?xml-stylesheet ...?>
            case Node.ENTITY_REFERENCE_NODE:
            case Node.DOCUMENT_TYPE_NODE:
                return false;
            default:
                break;
        }

        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!isSafeNode(child, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean areAttributesSafe(Element el) {
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            String name = localName(attr).toLowerCase();
            String value = attr.getValue() == null ? "" : attr.getValue().trim();
            String lowerValue = value.toLowerCase();

            // onload, onclick, onmouseover, onbegin, ...
            if (name.startsWith("on")) {
                return false;
            }
            // links must stay inside the document (covers both href and xlink:href)
            if ((name.equals("href") || name.endsWith(":href")) && !value.isEmpty() && !value.startsWith("#")) {
                return false;
            }
            String strippedValue = lowerValue.replaceAll("[\\s\\u0000-\\u001F]", "");
            if (strippedValue.contains("javascript:") || strippedValue.contains("vbscript:")
                    || strippedValue.startsWith("data:text/html")) {
                return false;
            }
            if (name.equals("style") && !isSafeCss(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeCss(String css) {
        if (css == null) return true;
        String lower = css.toLowerCase();
        if (lower.contains("@import") || lower.contains("javascript:")
                || lower.contains("expression(") || lower.contains("behavior:")
                || lower.contains("-moz-binding")) {
            return false;
        }
        Matcher m = CSS_URL.matcher(lower);
        while (m.find()) {
            if (!m.group(1).trim().startsWith("#")) {
                return false;   // only local url(#id) references allowed
            }
        }
        return true;
    }

    private static String localName(Node node) {
        String n = node.getLocalName();
        return n != null ? n : node.getNodeName();
    }
}