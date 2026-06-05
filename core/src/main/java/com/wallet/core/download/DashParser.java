package com.wallet.core.download;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses an MPEG-DASH {@code .mpd} manifest into a {@link Dash}: it picks the best
 * video {@code Representation} and expands its {@code SegmentTemplate} into a flat
 * list of segment URLs (init first). Supports {@code SegmentTimeline} and plain
 * {@code @duration} templates, the {@code $RepresentationID$ / $Number / $Time /
 * $Bandwidth$} placeholders (with {@code %0Nd} formatting), and chained
 * {@code <BaseURL>} resolution. SegmentList/SegmentBase-only manifests are a TODO.
 */
public final class DashParser {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$(RepresentationID|Bandwidth|Number|Time)(%0\\d+d)?\\$|\\$\\$");

    private DashParser() {}

    public static Dash parse(String xml, String baseUrl) {
        try {
            Element mpd = newDocument(xml);
            String base = applyBaseUrl(baseUrl, mpd);
            double presentation = parseIso8601Duration(attr(mpd, "mediaPresentationDuration"));

            Element period = firstChild(mpd, "Period");
            if (period == null) throw new IllegalArgumentException("no Period");
            base = applyBaseUrl(base, period);
            double periodDuration = parseIso8601Duration(attr(period, "duration"));
            double total = periodDuration > 0 ? periodDuration : presentation;

            Element bestRep = null, bestAdapt = null;
            long bestBw = -1;
            boolean bestVideo = false;
            for (Element as : childElements(period, "AdaptationSet")) {
                String asMime = attr(as, "mimeType");
                boolean asVideo = (asMime != null && asMime.startsWith("video"))
                        || "video".equals(attr(as, "contentType"));
                for (Element rep : childElements(as, "Representation")) {
                    String mime = attr(rep, "mimeType");
                    boolean isVideo = asVideo || (mime != null && mime.startsWith("video"));
                    long bw = parseLong(attr(rep, "bandwidth"), 0);
                    boolean better;
                    if (bestRep == null) better = true;
                    else if (isVideo != bestVideo) better = isVideo;          // prefer video
                    else better = bw > bestBw;                                 // then bandwidth
                    if (better) { bestRep = rep; bestAdapt = as; bestBw = bw; bestVideo = isVideo; }
                }
            }
            if (bestRep == null) throw new IllegalArgumentException("no Representation");
            base = applyBaseUrl(base, bestAdapt);
            base = applyBaseUrl(base, bestRep);

            String repId = attr(bestRep, "id");
            long bandwidth = parseLong(attr(bestRep, "bandwidth"), 0);
            int width = (int) parseLong(coalesce(attr(bestRep, "width"), attr(bestAdapt, "width")), 0);
            int height = (int) parseLong(coalesce(attr(bestRep, "height"), attr(bestAdapt, "height")), 0);
            String mimeType = coalesce(attr(bestRep, "mimeType"), attr(bestAdapt, "mimeType"));

            Element tmpl = firstChild(bestRep, "SegmentTemplate");
            if (tmpl == null) tmpl = firstChild(bestAdapt, "SegmentTemplate");
            if (tmpl == null) throw new IllegalArgumentException("only SegmentTemplate is supported");

            String initTmpl = attr(tmpl, "initialization");
            String mediaTmpl = attr(tmpl, "media");
            long startNumber = parseLong(attr(tmpl, "startNumber"), 1);
            long timescale = parseLong(attr(tmpl, "timescale"), 1);

            List<String> urls = new ArrayList<>();
            if (initTmpl != null) {
                urls.add(resolve(base, expand(initTmpl, repId, bandwidth, 0, 0)));
            }

            Element timeline = firstChild(tmpl, "SegmentTimeline");
            if (timeline != null) {
                long number = startNumber, time = 0;
                for (Element s : childElements(timeline, "S")) {
                    long t = parseLong(attr(s, "t"), -1);
                    long d = parseLong(attr(s, "d"), 0);
                    long r = parseLong(attr(s, "r"), 0);
                    if (t >= 0) time = t;
                    for (long k = 0; k <= r; k++) {
                        urls.add(resolve(base, expand(mediaTmpl, repId, bandwidth, number, time)));
                        number++;
                        time += d;
                    }
                }
            } else {
                long segDuration = parseLong(attr(tmpl, "duration"), 0);
                if (segDuration <= 0 || total <= 0) {
                    throw new IllegalArgumentException("cannot determine segment count");
                }
                long count = (long) Math.ceil(total * timescale / (double) segDuration);
                long number = startNumber, time = 0;
                for (long i = 0; i < count; i++) {
                    urls.add(resolve(base, expand(mediaTmpl, repId, bandwidth, number, time)));
                    number++;
                    time += segDuration;
                }
            }

            return new Dash(new Dash.Representation(repId, bandwidth, width, height, mimeType), urls);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("DASH parse failed: " + e.getMessage(), e);
        }
    }

    // --- template expansion --------------------------------------------------

    static String expand(String tmpl, String repId, long bandwidth, long number, long time) {
        if (tmpl == null) return null;
        Matcher m = PLACEHOLDER.matcher(tmpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement;
            if ("$$".equals(m.group())) {
                replacement = "$";
            } else {
                String name = m.group(1);
                String fmt = m.group(2);
                if ("RepresentationID".equals(name)) {
                    replacement = repId == null ? "" : repId;
                } else {
                    long value = "Bandwidth".equals(name) ? bandwidth
                            : "Number".equals(name) ? number
                            : time;                       // "Time"
                    replacement = fmt != null ? String.format(fmt, value) : Long.toString(value);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static double parseIso8601Duration(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?").matcher(s);
        if (!m.matches()) return 0;
        double d = 0;
        if (m.group(1) != null) d += Long.parseLong(m.group(1)) * 86400;
        if (m.group(2) != null) d += Long.parseLong(m.group(2)) * 3600;
        if (m.group(3) != null) d += Long.parseLong(m.group(3)) * 60;
        if (m.group(4) != null) d += Double.parseDouble(m.group(4));
        return d;
    }

    // --- DOM helpers ---------------------------------------------------------

    private static Element newDocument(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { }
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    private static List<Element> childElements(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(name)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String name) {
        List<Element> all = childElements(parent, name);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static String applyBaseUrl(String base, Element el) {
        Element b = firstChild(el, "BaseURL");
        if (b == null) return base;
        String text = b.getTextContent();
        if (text == null || text.trim().isEmpty()) return base;
        return resolve(base, text.trim());
    }

    private static String resolve(String base, String ref) {
        if (ref == null) return null;
        if (base == null) return ref;
        try {
            return URI.create(base).resolve(ref).toString();
        } catch (RuntimeException e) {
            return ref;
        }
    }

    private static long parseLong(String s, long dflt) {
        if (s == null) return dflt;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }
}
