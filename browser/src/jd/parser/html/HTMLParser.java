//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.parser.html;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jd.nutils.encoding.Encoding;
import jd.parser.Regex;

import org.appwork.utils.Application;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.Hex;
import org.appwork.utils.logging2.extmanager.LoggerFactory;
import org.appwork.utils.net.URLHelper;

public class HTMLParser {

    private static class ConcatCharSequence implements CharSequence {

        private final CharSequence[] charSequences;
        private final int            offset;
        private final int            end;
        private final int            length;

        public ConcatCharSequence(final CharSequence... charSequences) {
            this.charSequences = charSequences;
            int length = 0;
            for (final CharSequence charSequence : charSequences) {
                length += charSequence.length();
            }
            this.offset = 0;
            this.end = length;
            this.length = length;
        }

        private ConcatCharSequence(final int offset, final int start, final int end, ConcatCharSequence concatCharSequence) {
            this.charSequences = concatCharSequence.charSequences;
            this.end = end;
            this.offset = offset + start;
            this.length = end - start;
        }

        @Override
        public int length() {
            return this.length;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(this.length());
            sb.ensureCapacity(this.length());
            for (int index = 0; index < this.length(); index++) {
                sb.append(this.charAt(index));
            }
            return sb.toString();
        }

        @Override
        public char charAt(int index) {
            index = index + this.offset;
            final int offsetEnd = this.end + this.offset;
            if (index < 0 || index >= offsetEnd) {
                throw new IndexOutOfBoundsException("Index " + index);
            }
            int range = 0;
            for (final CharSequence charSequence : this.charSequences) {
                if (index < offsetEnd && index < range + charSequence.length()) {
                    return charSequence.charAt(index - range);
                }
                range += charSequence.length();
            }
            throw new IndexOutOfBoundsException("Index " + index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new ConcatCharSequence(this.offset, start, end, this);
        }
    }

    /**
     * this is an optimized CharSequence to reduce memory/performance impact of new string generation during substring/regex/replace stuff
     *
     * @author daniel
     *
     */
    public static class HtmlParserCharSequence implements CharSequence {

        private final static HashMap<Thread, HtmlParserCharSequence> THREADRESULTS = new HashMap<Thread, HtmlParserCharSequence>();

        final char[]                                                 chars;
        final CharSequence                                           charSequence;
        final int                                                    start;
        final int                                                    end;

        private HtmlParserCharSequence(final HtmlParserCharSequence source, final int start, final int end) {
            this.chars = source.chars;
            this.charSequence = source.charSequence;
            this.start = start;
            this.end = end;
        }

        private HtmlParserCharSequence(final CharSequence source) {
            this.chars = null;
            this.charSequence = source;
            this.start = 0;
            this.end = source.length();
        }

        @Override
        public char charAt(int index) {
            index = index + this.getStartIndex();
            if (index > this.getStopIndex()) {
                throw new IndexOutOfBoundsException("index " + index + " > end " + this.getStopIndex());
            }
            if (this.chars != null) {
                return this.chars[index];
            } else {
                return this.charSequence.charAt(index);
            }
        }

        int hashCodeCache = 0;
        int hashCode      = -1;

        @Override
        public int hashCode() {
            if (this.hashCodeCache != this.hashCode) {
                int h = 0;
                final int length = this.length();
                if (length > 0) {
                    for (int i = 0; i < length; i++) {
                        h = 31 * h + this.charAt(i);
                    }
                }
                this.hashCodeCache = h;
                this.hashCode = h;
                return h;
            }
            return this.hashCodeCache;
        }

        public boolean contains(final CharSequence s) {
            return this.indexOf(s, 0) >= 0;
        }

        @Override
        public boolean equals(final Object anObject) {
            if (this == anObject) {
                return true;
            }
            if (anObject != null && anObject instanceof CharSequence) {
                final CharSequence anotherString = (CharSequence) anObject;
                int n = this.length();
                if (n == anotherString.length()) {
                    int i = 0;
                    while (n-- != 0) {
                        if (this.charAt(i) != anotherString.charAt(i)) {
                            return false;
                        }
                        i++;
                    }
                    return true;
                }
            }
            return false;
        }

        public int getStartIndex() {
            return this.start;
        }

        public int getStopIndex() {
            return this.end;
        }

        private int indexOf(final int sourceOffset, final int sourceCount, final CharSequence target, final int targetOffset, final int targetCount, int fromIndex) {
            if (fromIndex >= sourceCount) {
                return targetCount == 0 ? sourceCount : -1;
            }
            if (fromIndex < 0) {
                fromIndex = 0;
            }
            if (targetCount == 0) {
                return fromIndex;
            }

            final char first = target.charAt(targetOffset);
            final int max = sourceOffset + sourceCount - targetCount;
            if (this.chars != null) {
                /* we have a char array */
                for (int i = sourceOffset + fromIndex; i <= max; i++) {
                    /* Look for first character. */
                    if (this.chars[i] != first) {
                        while (++i <= max && this.chars[i] != first) {
                            ;
                        }
                    }
                    /* Found first character, now look at the rest of v2 */
                    if (i <= max) {
                        int j = i + 1;
                        final int end = j + targetCount - 1;
                        for (int k = targetOffset + 1; j < end && this.chars[j] == target.charAt(k); j++, k++) {
                            ;
                        }
                        if (j == end) {
                            /* Found whole string. */
                            return i - sourceOffset;
                        }
                    }
                }
            } else {
                /* we have a charSequence */
                for (int i = sourceOffset + fromIndex; i <= max; i++) {
                    /* Look for first character. */
                    if (this.charSequence.charAt(i) != first) {
                        while (++i <= max && this.charSequence.charAt(i) != first) {
                            ;
                        }
                    }
                    /* Found first character, now look at the rest of v2 */
                    if (i <= max) {
                        int j = i + 1;
                        final int end = j + targetCount - 1;
                        for (int k = targetOffset + 1; j < end && this.charSequence.charAt(j) == target.charAt(k); j++, k++) {
                            ;
                        }
                        if (j == end) {
                            /* Found whole string. */
                            return i - sourceOffset;
                        }
                    }
                }
            }
            return -1;
        }

        public int indexOf(final CharSequence str) {
            return this.indexOf(str, 0);
        }

        public int indexOf(final CharSequence indexOf, final int fromIndex) {
            return this.indexOf(this.getStartIndex(), this.length(), indexOf, 0, indexOf.length(), fromIndex);
        }

        @Override
        public int length() {
            return this.getStopIndex() - this.getStartIndex();
        }

        public boolean matches(final Pattern regex) {
            return regex.matcher(this).matches();
        }

        public boolean find(final Pattern regex) {
            return this.count(regex, 1) > 0;
        }

        public int count(final Pattern regex, int countMax) {
            final Matcher matcher = regex.matcher(this);
            int ret = 0;
            while (matcher.find()) {
                ret++;
                if (ret >= countMax) {
                    break;
                }
            }
            return ret;
        }

        public HtmlParserCharSequence replaceAll(final Pattern regex, final String replacement) {
            if (replacement == null) {
                throw new NullPointerException("replacement");
            }
            final Matcher matcher = regex.matcher(this);
            matcher.reset();
            if (!matcher.find()) {
                return this;
            }
            final StringBuffer sb = new StringBuffer();
            do {
                matcher.appendReplacement(sb, replacement);
            } while (matcher.find());
            matcher.appendTail(sb);
            return new HtmlParserCharSequence(sb);
        }

        public HtmlParserCharSequence replaceFirst(final Pattern regex, final String replacement) {
            if (replacement == null) {
                throw new NullPointerException("replacement");
            }
            final Matcher matcher = regex.matcher(this);
            matcher.reset();
            if (!matcher.find()) {
                return this;
            }
            final StringBuffer sb = new StringBuffer();
            matcher.appendReplacement(sb, replacement);
            matcher.appendTail(sb);
            return new HtmlParserCharSequence(sb);
        }

        public boolean startsWith(final CharSequence prefix) {
            return this.startsWith(prefix, 0);
        }

        public boolean startsWith(final CharSequence prefix, final int toffset) {
            int to = toffset;
            int po = 0;
            int pc = prefix.length();
            // Note: toffset might be near -1>>>1.
            if (toffset < 0 || toffset > this.length() - pc) {
                return false;
            }
            while (--pc >= 0) {
                if (this.charAt(to++) != prefix.charAt(po++)) {
                    return false;
                }
            }
            return true;
        }

        public List<HtmlParserCharSequence> getColumn(int group, Pattern pattern) {
            final Matcher matcher = pattern.matcher(this);
            final List<HtmlParserCharSequence> result = new ArrayList<HtmlParserCharSequence>();
            while (true) {
                final HtmlParserCharSequence match = this.group(group, matcher);
                if (match != null) {
                    result.add(match);
                } else {
                    break;
                }
            }
            return result;
        }

        private HtmlParserCharSequence group(int group, Matcher matcher) {
            if (!matcher.find()) {
                return null;
            }
            final Thread currentThread = Thread.currentThread();
            synchronized (HtmlParserCharSequence.THREADRESULTS) {
                HtmlParserCharSequence.THREADRESULTS.put(currentThread, null);
            }
            try {
                final String stringResult = matcher.group(group);
                final HtmlParserCharSequence htmlParserResult;
                synchronized (HtmlParserCharSequence.THREADRESULTS) {
                    htmlParserResult = HtmlParserCharSequence.THREADRESULTS.get(currentThread);
                }
                if (htmlParserResult != null) {
                    return htmlParserResult;
                }
                if (stringResult == null) {
                    return null;
                }
                return new HtmlParserCharSequence(stringResult);
            } finally {
                synchronized (HtmlParserCharSequence.THREADRESULTS) {
                    HtmlParserCharSequence.THREADRESULTS.remove(currentThread);
                }
            }
        }

        public HtmlParserCharSequence group(int group, Pattern pattern) {
            final Matcher matcher = pattern.matcher(this);
            return this.group(group, matcher);
        }

        public HtmlParserCharSequence subSequence(final int start) {
            final Thread currentThread = Thread.currentThread();
            final HtmlParserCharSequence ret;
            if (start > 0) {
                ret = new HtmlParserCharSequence(this, this.getStartIndex() + start, this.getStopIndex());
            } else {
                ret = this;
            }
            synchronized (HtmlParserCharSequence.THREADRESULTS) {
                if (HtmlParserCharSequence.THREADRESULTS.containsKey(currentThread)) {
                    HtmlParserCharSequence.THREADRESULTS.put(currentThread, ret);
                }
            }
            return ret;
        }

        @Override
        public HtmlParserCharSequence subSequence(final int start, final int end) {
            final Thread currentThread = Thread.currentThread();
            final HtmlParserCharSequence ret;
            if (start > 0 || end < this.getStopIndex()) {
                ret = new HtmlParserCharSequence(this, this.getStartIndex() + start, this.getStartIndex() + end);
            } else {
                ret = this;
            }
            synchronized (HtmlParserCharSequence.THREADRESULTS) {
                if (HtmlParserCharSequence.THREADRESULTS.containsKey(currentThread)) {
                    HtmlParserCharSequence.THREADRESULTS.put(currentThread, ret);
                }
            }
            return ret;
        }

        @Override
        public String toString() {
            synchronized (HtmlParserCharSequence.THREADRESULTS) {
                if (HtmlParserCharSequence.THREADRESULTS.containsKey(Thread.currentThread())) {
                    return null;
                }
            }
            if (this.chars != null) {
                return new String(this.chars, this.getStartIndex(), this.length());
            } else {
                return this.charSequence.subSequence(this.getStartIndex(), this.getStopIndex()).toString();
            }
        }

        public String toURL() {
            final String ret = this.toString();
            return Encoding.urlEncode_light(ret);
        }

        public HtmlParserCharSequence trim() {
            int len = this.length();
            int st = 0;
            while (st < len && this.charAt(st) <= ' ') {
                st++;
            }
            while (st < len && this.charAt(len - 1) <= ' ') {
                len--;
            }
            return st > 0 || len < this.length() ? this.subSequence(st, len) : this;

        }
    }

    public static class HtmlParserResultSet {

        protected final ArrayList<HtmlParserCharSequence> results     = new ArrayList<HtmlParserCharSequence>();
        protected final HashSet<HtmlParserCharSequence>   dupeCheck   = new HashSet<HTMLParser.HtmlParserCharSequence>();

        private HtmlParserCharSequence                    baseURL     = null;
        private boolean                                   skipBaseURL = false;

        protected void setSkipBaseURL(boolean skipBaseURL) {
            this.skipBaseURL = skipBaseURL;
        }

        protected boolean isSkipBaseURL() {
            return this.skipBaseURL;
        }

        public HtmlParserCharSequence getBaseURL() {
            return this.baseURL;
        }

        private void setBaseURL(HtmlParserCharSequence baseURL) {
            if (baseURL != null && !baseURL.equals("about:blank")) {
                this.baseURL = baseURL;
            }
        }

        public boolean add(HtmlParserCharSequence e) {
            if (e != null) {
                if (this.dupeCheck.add(e)) {
                    this.results.add(e);
                    return true;
                }
            }
            return false;
        }

        public boolean remove(HtmlParserCharSequence e) {
            if (e != null) {
                if (this.dupeCheck.remove(e)) {
                    this.results.remove(e);
                    return true;
                }
            }
            return false;
        }

        public int getLastResultIndex() {
            return this.results.size();
        }

        protected List<HtmlParserCharSequence> getResults() {
            return this.results;
        }

        protected LinkedHashSet<String> exportResults() {
            final LinkedHashSet<String> ret = new LinkedHashSet<String>();
            for (final HtmlParserCharSequence result : this.getResults()) {
                final String url = result.toURL();
                if (StringUtils.isNotEmpty(url)) {
                    ret.add(url);
                }
            }
            return ret;
        }

        public List<HtmlParserCharSequence> getResultsSublist(final int index) {
            return this.results.subList(index, this.results.size());
        }

        public boolean contains(HtmlParserCharSequence data) {
            return data != null && this.dupeCheck.contains(data);
        }
    }

    final static class Httppattern {
        public Pattern p;
        public int     group;

        public Httppattern(final Pattern p, final int group) {
            this.p = p;
            this.group = group;
        }
    }

    final private static Httppattern[]          linkAndFormPattern          = new Httppattern[] { new Httppattern(Pattern.compile("src.*?=.*?('|\\\\\"|\")(.*?)(\\1)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL), 2), new Httppattern(Pattern.compile("(<[ ]?a[^>]*?href=|<[ ]?form[^>]*?action=)('|\\\\\"|\")(.*?)(\\2)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL), 3), new Httppattern(Pattern.compile("(<[ ]?a[^>]*?href=|<[ ]?form[^>]*?action=)([^'\"][^\\s]*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL), 2), new Httppattern(Pattern.compile("\\[(link|url)\\](.*?)\\[/(link|url)\\]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL), 2) };
    public final static String                  protocolFile                = "file:/";
    final private static String                 protocolPrefixes            = "((?:mega|chrome|directhttp://https?|usenet|flashget|https?viajd|https?|ccf|dlc|ftp|ftpviajd|jd|rsdf|jdlist|youtubev2" + (!Application.isJared(null) ? "|jdlog" : "") + ")://|" + HTMLParser.protocolFile + "|magnet:)";
    final private static Pattern[]              basePattern                 = new Pattern[] { Pattern.compile("base[^>]*?href=('|\")(.*?)\\1", Pattern.CASE_INSENSITIVE), Pattern.compile("base[^>]*?(href)=([^'\"][^\\s]*)", Pattern.CASE_INSENSITIVE) };
    final private static Pattern[]              hrefPattern                 = new Pattern[] { Pattern.compile("href=('|\")(.*?)(?:\\s*?)(\\1)", Pattern.CASE_INSENSITIVE), Pattern.compile("src=('|\")(.*?)(?:\\s*?)(\\1)", Pattern.CASE_INSENSITIVE) };
    final private static Pattern                pat1                        = Pattern.compile("(" + HTMLParser.protocolPrefixes + "|(?<!://)www\\.)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                protocols                   = Pattern.compile("(" + HTMLParser.protocolPrefixes + ")");
    final private static Pattern                LINKPROTOCOL                = Pattern.compile("^" + HTMLParser.protocolPrefixes, Pattern.CASE_INSENSITIVE);

    final private static Pattern                mergePattern_Root           = Pattern.compile("(.*?\\..*?)(/|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                mergePattern_Path           = Pattern.compile("(.*?\\.[^?#]+/)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                mergePattern_FileORPath     = Pattern.compile("(.*?\\..*?/.*?)($|#|\\?)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static Pattern                      mp                          = null;

    static {
        try {
            HTMLParser.mp = Pattern.compile("(\\\\\"|\"|')?((" + HTMLParser.protocolPrefixes + "|www\\.).+?(?=((\\s+" + HTMLParser.protocolPrefixes + ")|\\1|<|>|\\[/|\r|\n|\f|\t|$|';|'\\)|\"\\s*|'\\+)))", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } catch (final Throwable e) {
            org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().log(e);
        }
    }

    final private static Pattern                unescapePattern             = Pattern.compile("unescape\\(('|\")(.*?)(\\1)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                checkPatternHREFUNESCAPESRC = Pattern.compile(".*?(href|unescape|src=).+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                checkPatternHREFSRC         = Pattern.compile(".*?(href|src=).+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                unhexPattern                = Pattern.compile("(([0-9a-fA-F]{2}| )+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static Pattern                paramsCut1                  = Pattern.compile("://[^\r\n]*?/[^\r\n]+\\?.[^\r\n]*?=(.*?)($|\r|\n)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static Pattern                paramsCut2                  = Pattern.compile("://[^\r\n]*?/[^\r\n]*?\\?(.*?)($|\r|\n)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern                inTagsPattern               = Pattern.compile("<([^<]*?)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static Pattern                endTagPattern               = Pattern.compile("^(.*?)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static Pattern                taglessPattern              = Pattern.compile("^(.*?)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static HtmlParserCharSequence directHTTP                  = new HtmlParserCharSequence("directhttp://");
    final private static HtmlParserCharSequence httpviajd                   = new HtmlParserCharSequence("httpviajd://");
    final private static HtmlParserCharSequence httpsviajd                  = new HtmlParserCharSequence("httpsviajd://");
    final private static Pattern                httpRescue                  = Pattern.compile("h.{2,3}://");

    final private static Pattern                tagsPattern                 = Pattern.compile(".*<.*>.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static Pattern                singleSpacePattern          = Pattern.compile(" ", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                space2Pattern               = Pattern.compile(".*\\s.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                hdotsPattern                = Pattern.compile("h.{2,3}://", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                specialReplacePattern       = Pattern.compile("'", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static Pattern                urlReplaceBracketOpen       = Pattern.compile("\\(", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                urlReplaceBracketClose      = Pattern.compile("\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final private static Pattern                missingHTTPPattern          = Pattern.compile("^www\\.", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final private static Pattern                removeTagsPattern           = Pattern.compile("[<>\"]*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // full | double full | partial | partial | partial | partial | partial | partial
    final private static Pattern                urlEncodedProtocol          = Pattern.compile("(%3A%2F%2F|%253A%252F%252F|%3A//|%3A%2F/|%3A/%2F|:%2F%2F|:%2F/|:/%2F)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static HtmlParserResultSet _getHttpLinksDeepWalker(HtmlParserCharSequence data, HtmlParserResultSet results) {
        if (results == null) {
            results = new HtmlParserResultSet();
        }
        if (data != null) {
            data = data.trim();
        }
        if (data == null || data.length() < 13) {
            return results;
        }
        if ((data.startsWith(HTMLParser.directHTTP) || data.startsWith(HTMLParser.httpviajd) || data.startsWith(HTMLParser.httpsviajd)) && results.contains(data)) {
            /* we don't have to further check urls with those prefixes */
            return results;
        }
        final int indexBefore = results.getLastResultIndex();
        /* find reversed */
        CharSequence reversedata = new StringBuilder(data).reverse();
        if (!data.equals(reversedata)) {
            HTMLParser._getHttpLinksFinder(new HtmlParserCharSequence(reversedata), results);
        }
        reversedata = null;
        /* find base64'ed */
        final HtmlParserCharSequence urlDecoded = HTMLParser.decodeURLParamEncodedURL(data);
        // check for non base64 chars here -> speed up
        CharSequence base64Data = Encoding.Base64Decode(urlDecoded);
        if (urlDecoded.equals(base64Data)) {
            /* no base64 content found */
            base64Data = null;
        }
        if (base64Data != null && !data.equals(base64Data)) {
            HTMLParser._getHttpLinksFinder(new HtmlParserCharSequence(base64Data), results);
        }
        base64Data = null;
        /* parse escaped js stuff */
        if (data.length() > 23 && data.contains("unescape")) {
            final List<HtmlParserCharSequence> unescaped = data.getColumn(2, HTMLParser.unescapePattern);
            if (unescaped != null && unescaped.size() > 0) {
                for (HtmlParserCharSequence unescape : unescaped) {
                    HTMLParser._getHttpLinksFinder(new HtmlParserCharSequence(Encoding.htmlDecode(unescape.toString())), results);
                }
            }
        }
        /* find hex'ed */
        if (HTMLParser.deepWalkCheck(results, indexBefore) && data.length() >= 24) {
            HtmlParserCharSequence hex = data.group(1, HTMLParser.unhexPattern);
            if (hex != null && hex.length() >= 24) {
                try {
                    /* remove spaces from hex-coded string */
                    hex = hex.replaceAll(Pattern.compile(" "), "");
                    final String hexString = Hex.hex2String(hex);
                    hex = new HtmlParserCharSequence(hexString);
                    HTMLParser._getHttpLinksFinder(hex, results);
                } catch (final Throwable e) {
                    org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger().log(e);
                }
            }
        }
        if (HTMLParser.deepWalkCheck(results, indexBefore) && !urlDecoded.equals(data)) {
            /* no changes in results size, and data contains urlcoded http://, so lets urldecode it */
            HTMLParser._getHttpLinksFinder(urlDecoded, results);
        }
        return results;
    }

    private static HtmlParserResultSet _getHttpLinksFinder(HtmlParserCharSequence data, HtmlParserResultSet results) {
        if (results == null) {
            results = new HtmlParserResultSet();
        }
        if (data != null) {
            data = data.trim();
        }
        if (data == null || data.length() == 0) {
            return results;
        }
        if (data.contains(":\\/\\/")) {
            /**
             * \\ escaped urls, eg JSON
             */
            data = data.replaceAll(Pattern.compile("\\\\"), "");
        }
        if (!data.matches(HTMLParser.tagsPattern)) {
            final int c = data.count(HTMLParser.pat1, 2);
            if (c == 0) {
                if (!data.matches(HTMLParser.checkPatternHREFSRC)) {
                    /* no href inside */
                    return results;
                }
            } else if (c == 1 && data.length() < 256) {
                HtmlParserCharSequence protocol = null;
                HtmlParserCharSequence link = data;
                if ((protocol = HTMLParser.getProtocol(link)) == null && !link.contains("%2F")) {
                    link = data.replaceFirst(HTMLParser.hdotsPattern, "http://").replaceFirst(HTMLParser.missingHTTPPattern, "http://www.");
                }
                if ((protocol = HTMLParser.getProtocol(link)) != null) {
                    if (protocol.startsWith(HTMLParser.protocolFile)) {
                        results.add(link);
                        return results;
                    } else {
                        link = link.replaceAll(HTMLParser.removeTagsPattern, "");
                        if (!link.matches(HTMLParser.space2Pattern)) {
                            results.add(HTMLParser.correctURL(link));
                            return results;
                        }
                    }
                }
            }
        }
        HtmlParserCharSequence baseURL = results.getBaseURL();
        if (baseURL == null && !results.isSkipBaseURL()) {
            for (final Pattern pattern : HTMLParser.basePattern) {
                final HtmlParserCharSequence found = data.group(2, pattern);
                if (found != null) {
                    if (!found.equals("about:blank")) {
                        baseURL = found;
                        break;
                    }
                }
            }
            if (HTMLParser.getProtocol(baseURL) != null) {
                results.setBaseURL(baseURL);
            } else {
                baseURL = null;
            }
            results.setSkipBaseURL(true);
        }

        HtmlParserCharSequence hrefURL = null;
        for (final Pattern pattern : HTMLParser.hrefPattern) {
            final HtmlParserCharSequence found = data.group(2, pattern);
            if (found != null) {
                if (!found.equals("about:blank") && !found.equals("/") && !found.startsWith("#")) {
                    hrefURL = found;
                    break;
                }
            }
        }
        HtmlParserCharSequence protocol = HTMLParser.getProtocol(hrefURL);
        if (protocol == null) {
            if (baseURL != null && hrefURL != null) {
                hrefURL = HTMLParser.mergeUrl(baseURL, hrefURL);
            } else {
                /* no baseURL available, we are unable to try mergeURL */
                hrefURL = null;
            }
        }
        if (protocol != null || (protocol = HTMLParser.getProtocol(hrefURL)) != null) {
            /* found a valid url with recognized protocol */
            results.add(HTMLParser.correctURL(hrefURL));
            if (data.equals(hrefURL)) {
                return results;
            }
        }

        for (final Httppattern element : HTMLParser.linkAndFormPattern) {
            final Matcher m = element.p.matcher(data);
            while (true) {
                HtmlParserCharSequence link = data.group(element.group, m);
                if (link == null) {
                    break;
                }
                protocol = HTMLParser.getProtocol(link);
                if (protocol == null) {
                    link = link.replaceAll(HTMLParser.httpRescue, "http://");
                }
                if (protocol == null && baseURL != null && (protocol = HTMLParser.getProtocol(link)) == null) {
                    link = HTMLParser.mergeUrl(baseURL, link);
                }
                if (protocol != null || (protocol = HTMLParser.getProtocol(link)) != null) {
                    results.add(HTMLParser.correctURL(link));
                    if (data.equals(link)) {
                        return results;
                    }
                }
            }
        }
        if (HTMLParser.mp != null) {
            final Matcher m = HTMLParser.mp.matcher(data);
            while (true) {
                HtmlParserCharSequence link = data.group(2, m);
                if (link == null) {
                    break;
                }
                link = link.trim();
                if (HTMLParser.getProtocol(link) == null && !link.contains("%2F")) {
                    link = link.replaceFirst(HTMLParser.missingHTTPPattern, "http://www\\.");
                }
                results.add(HTMLParser.correctURL(link));
                final Matcher mlinks = HTMLParser.protocols.matcher(link);
                int start = -1;
                /*
                 * special handling if we have multiple links without newline separation
                 */
                while (mlinks.find()) {
                    if (start == -1) {
                        start = mlinks.start();
                    } else {
                        results.add(HTMLParser.correctURL(link.subSequence(start, mlinks.start())));
                        start = mlinks.start();
                    }
                }
                if (start != -1) {
                    link = link.subSequence(start);
                    results.add(HTMLParser.correctURL(link));
                    if (data.equals(link)) {
                        /* data equals check, so we can leave this loop */
                        return results;
                    }
                }
            }
        }
        return results;
    }

    private final static String TAGOPEN  = String.valueOf('<');
    private final static String TAGCLOSE = String.valueOf('>');

    private static HtmlParserResultSet _getHttpLinksWalker(HtmlParserCharSequence data, HtmlParserResultSet results, Pattern tagRegex) {
        // System.out.println("Call: "+data.length());
        if (results == null) {
            results = new HtmlParserResultSet();
        }
        if (data != null) {
            data = data.trim();
        }
        if (data == null || data.length() < 13) {
            return results;
        }
        /* filtering tags, recursion command me ;) */
        while (true) {
            if (tagRegex == null) {
                tagRegex = HTMLParser.inTagsPattern;
            }
            HtmlParserCharSequence nexttag = data.group(1, tagRegex);
            if (nexttag == null || nexttag.length() == 0) {
                /* no further tag found, lets continue */
                break;
            } else {
                /* lets check if tag contains links */
                HTMLParser._getHttpLinksWalker(nexttag, results, HTMLParser.inTagsPattern);
                final int tagOpen = data.indexOf(new ConcatCharSequence(HTMLParser.TAGOPEN, nexttag));
                int tagClose = -1;
                if (tagOpen >= 0) {
                    tagClose = tagOpen + nexttag.length() + 1;
                }
                if (tagClose >= 0 && data.length() >= tagClose + 1) {
                    if (tagOpen > 0) {
                        /*
                         * there might be some data left before the tag, do not remove that data
                         */
                        final HtmlParserCharSequence dataLeft = data.subSequence(0, tagOpen);
                        final HtmlParserCharSequence dataLeft2 = data.subSequence(tagClose + 1);
                        data = null;
                        if (dataLeft.contains(HTMLParser.TAGCLOSE)) {
                            HTMLParser._getHttpLinksWalker(dataLeft, results, HTMLParser.endTagPattern);
                        } else {
                            HTMLParser._getHttpLinksWalker(dataLeft, results, HTMLParser.taglessPattern);
                        }
                        data = dataLeft2;
                    } else {
                        /* remove tag at begin of data */
                        data = data.subSequence(tagClose + 1);
                        if (data.length() == 0) {
                            return results;
                        }
                    }
                    // System.out.println("SubCall: "+data.length());
                } else {
                    if (tagClose < 0) {
                        data = data.subSequence(nexttag.length());
                        if (data.length() == 0) {
                            return results;
                        }
                    } else {
                        /* remove tag at begin of data */
                        data = data.subSequence(tagClose + 1);
                        if (data.length() == 0) {
                            return results;
                        }
                    }
                }
            }
        }
        /* find normal */
        if (data.length() < 13) {
            //
            return results;
        }
        if (!data.contains("://") && !data.contains(HTMLParser.protocolFile) && !data.contains(":\\/\\/") && !data.find(HTMLParser.urlEncodedProtocol) && !data.contains("www.")) {
            /* data must contain at least the protocol separator */
            if (!data.matches(HTMLParser.checkPatternHREFUNESCAPESRC)) {
                /* maybe easy encrypted website or a href */
                return results;
            }
        }
        final int indexBefore = results.getResults().size();
        HTMLParser._getHttpLinksFinder(data, results);
        if (HTMLParser.deepWalkCheck(results, indexBefore)) {
            HTMLParser._getHttpLinksDeepWalker(data, results);
            /* cut of ?xy= parts if needed */
            HtmlParserCharSequence newdata = data.group(1, HTMLParser.paramsCut1);
            if (newdata != null && !data.equals(newdata)) {
                HTMLParser._getHttpLinksDeepWalker(newdata, results);
            }
            /* use of ?xy parts if available */
            newdata = data.group(1, HTMLParser.paramsCut2);
            if (newdata != null && !data.equals(newdata)) {
                HTMLParser._getHttpLinksDeepWalker(newdata, results);
            }
        }
        return results;
    }

    private static HtmlParserCharSequence correctURL(HtmlParserCharSequence input) {
        final int specialCutOff = input.indexOf("', ");
        if (specialCutOff >= 0) {
            input = input.subSequence(0, specialCutOff);
        }

        final int indexofa = input.indexOf("&");
        if (indexofa > 0 && input.indexOf("?") == -1) {
            final int indexofb = input.indexOf("#");
            if (indexofb < 0 || indexofb > indexofa) {
                /**
                 * this can happen when we found a link as urlparameter
                 *
                 * eg test.com/?u=http%3A%2F%2Fwww...&bla=
                 *
                 * then we get
                 *
                 * http://www...&bla
                 *
                 * we cut of the invalid urlParameter &bla
                 *
                 * check if we really have &x=y format following
                 *
                 * also pay attention about anchor
                 */
                final HtmlParserCharSequence check = input.subSequence(indexofa);
                final int indexChecka = check.indexOf("=");
                if (indexChecka > 0) {
                    final HtmlParserCharSequence check2 = check.subSequence(1, indexChecka);
                    if (check2.matches(Pattern.compile("[a-zA-Z0-9%]+"))) {
                        /* we have found &x=y pattern, so it is okay to cut it off */
                        input = input.subSequence(0, indexofa);
                    }
                }
            }
        }
        try {
            final URL url = new URL(input.toString());
            final String originalPath = url.getPath();
            if (originalPath != null) {
                String path = originalPath;
                path = HTMLParser.urlReplaceBracketOpen.matcher(path).replaceAll("%28");
                path = HTMLParser.urlReplaceBracketClose.matcher(path).replaceAll("%29");
                path = HTMLParser.specialReplacePattern.matcher(path).replaceAll("%27");
                path = HTMLParser.singleSpacePattern.matcher(path).replaceAll("%20");
                if (!originalPath.equals(path)) {
                    final String ret = URLHelper.createURL(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), path, url.getQuery(), url.getRef());
                    return new HtmlParserCharSequence(ret);
                }
            }
        } catch (Throwable e) {
            if (!input.matches(Pattern.compile("^" + HTMLParser.protocolPrefixes + ".+"))) {
                LoggerFactory.getDefaultLogger().log(e);
            }
        }
        return input;
    }

    private final static LinkedHashMap<Pattern, String> URLDECODE = new LinkedHashMap<Pattern, String>();
    static {
        // has to be first. to allow for multiple double encode of % eg. %253A%252F%252F
        HTMLParser.URLDECODE.put(Pattern.compile("%25"), "%");
        // rest can be in any order
        HTMLParser.URLDECODE.put(Pattern.compile("%2F"), "/");
        HTMLParser.URLDECODE.put(Pattern.compile("%3A"), ":");
        HTMLParser.URLDECODE.put(Pattern.compile("%3F"), "?");
        HTMLParser.URLDECODE.put(Pattern.compile("%3D"), "=");
        HTMLParser.URLDECODE.put(Pattern.compile("%26"), "&");
        HTMLParser.URLDECODE.put(Pattern.compile("%23"), "#");
    }

    public static HtmlParserCharSequence decodeURLParamEncodedURL(HtmlParserCharSequence url) {
        if (url != null && url.find(HTMLParser.urlEncodedProtocol)) {
            for (Entry<Pattern, String> replace : HTMLParser.URLDECODE.entrySet()) {
                url = url.replaceAll(replace.getKey(), replace.getValue());
            }
        }
        return url;
    }

    public static String decodeURLParamEncodedURL(String url) {
        if (url != null) {
            final HtmlParserCharSequence url2 = new HtmlParserCharSequence(url);
            final HtmlParserCharSequence ret = HTMLParser.decodeURLParamEncodedURL(url2);
            if (url2 != ret) {
                url = url2.toURL();
            }
        }
        return url;
    }

    private static boolean deepWalkCheck(final HtmlParserResultSet results, final int indexBefore) {
        final int latestIndex = results.getLastResultIndex();
        final boolean ret = latestIndex == indexBefore;
        if (!ret) {
            final List<HtmlParserCharSequence> subList = results.getResultsSublist(indexBefore);
            for (final HtmlParserCharSequence check : subList) {
                if (check.find(HTMLParser.urlEncodedProtocol)) {
                    return true;
                }
            }
        }
        return ret;
    }

    /**
     * Diese Methode sucht die vordefinierten input type="hidden" und formatiert sie zu einem poststring z.b. wÃ¼rde bei:
     *
     * <input type="hidden" name="f" value="f50b0f" /> <input type="hidden" name="h" value="390b4be0182b85b0" /> <input type="hidden"
     * name="b" value="9" />
     *
     * f=f50b0f&h=390b4be0182b85b0&b=9 ausgegeben werden
     *
     * @param data
     *            Der zu durchsuchende Text
     *
     * @return ein String, der als POST Parameter genutzt werden kann und alle Parameter des Formulars enthÃ¤lt
     */
    public static String getFormInputHidden(final String data) {
        return HTMLParser.joinMap(HTMLParser.getInputHiddenFields(data), "=", "&");
    }

    public static String[] getHttpLinks(final String data, final String url) {
        return HTMLParser.getHttpLinks(data, url, null);
    }

    public static String[] getHttpLinks(final String data, final String url, HtmlParserResultSet results) {
        HashSet<String> links = HTMLParser.getHttpLinksIntern(data, url, results);
        if (links == null || links.size() == 0) {
            return new String[0];
        }
        /*
         * in case we have valid and invalid (...) urls for the same link, we only use the valid one
         */
        final LinkedHashSet<String> tmplinks = new LinkedHashSet<String>(links.size());
        for (final String link : links) {
            if (link.contains("...")) {
                final String check = link.substring(0, link.indexOf("..."));
                String found = link;
                for (final String link2 : links) {
                    if (link2.startsWith(check) && !link2.contains("...")) {
                        found = link2;
                        break;
                    }
                }
                tmplinks.add(found);
            } else {
                tmplinks.add(link);
            }
            // this finds a URLencoded URL within 'link'. We only want to find URLEncoded link, and not a value belonging to 'link'
            final String urlEncodedLink = new Regex(link, "(?:https?|ftp)" + HTMLParser.urlEncodedProtocol + "[^&]+").getMatch(-1);
            if (urlEncodedLink != null) {
                tmplinks.add(HTMLParser.decodeURLParamEncodedURL(urlEncodedLink));
            }
        }
        links = null;
        return tmplinks.toArray(new String[tmplinks.size()]);
    }

    public static HashSet<String> getHttpLinksIntern(String content, final String baseURLString) {
        return HTMLParser.getHttpLinksIntern(content, baseURLString, null);
    }

    /*
     * return tmplinks.toArray(new String[tmplinks.size()]); }
     *
     * /* parses data for available links and returns a string array which does not contain any duplicates
     */
    public static HashSet<String> getHttpLinksIntern(String content, final String baseURLString, HtmlParserResultSet results) {
        if (content == null || content.length() == 0) {
            return null;
        }
        HtmlParserCharSequence data = new HtmlParserCharSequence(content);
        data = data.trim();
        if (data.length() == 0) {
            return null;
        }
        /*
         * replace urlencoded br tags, so we can find all links separated by those
         */
        /* find a better solution for this html codings */
        data = data.replaceAll(Pattern.compile("&lt;"), ">");
        data = data.replaceAll(Pattern.compile("&gt;"), "<");
        data = data.replaceAll(Pattern.compile("&amp;"), "&");
        data = data.replaceAll(Pattern.compile("&quot;"), "\"");
        /* place all replaces here that separates links */
        /* replace <br> tags with space so we we can separate the links */
        /* we replace the complete br tag with a newline */
        data = data.replaceAll(Pattern.compile("<br.*?>"), "\r\n");
        /* remove word breaks */
        data = data.replaceAll(Pattern.compile("<wbr.*?>"), "");
        /* remove HTML Tags */
        data = data.replaceAll(Pattern.compile("</?(i|b|u|s)>"), "");
        /*
         * remove all span because they can break url parsing (eg when google-code-prettify is used)
         */
        // not needed here because our filter below will take care of them
        // data = data.replaceAll("(?i)<span.*?>", "");
        // data = data.replaceAll("(?i)</span.*?>", "");
        data = data.replaceAll(Pattern.compile("(?s)\\[(url|link)\\](.*?)\\[/(\\2)\\]"), "<$2>");

        final HtmlParserResultSet resultSet;
        if (results != null) {
            resultSet = results;
        } else {
            resultSet = new HtmlParserResultSet();
        }
        if (baseURLString != null && HTMLParser.getProtocol(baseURLString) != null) {
            resultSet.setBaseURL(new HtmlParserCharSequence(baseURLString));
        }
        HTMLParser._getHttpLinksWalker(data, resultSet, null);
        data = null;
        /* we don't want baseurl to be included in result set */
        resultSet.remove(resultSet.getBaseURL());
        // System.out.println("Walker:" + results.getWalkerCounter() + "|DeepWalker:" + results.getDeepWalkerCounter() + "|Finder:" +
        // results.getFinderCounter() + "|Found:" + results.size());
        final LinkedHashSet<String> ret = resultSet.exportResults();
        if (ret.size() == 0) {
            return null;
        } else {
            return ret;
        }
    }

    /**
     * Gibt alle Hidden fields als hasMap zurÃ¼ck
     *
     * @param data
     * @return hasmap mit allen hidden fields variablen
     */
    public static HashMap<String, String> getInputHiddenFields(final String data) {
        final Pattern intput1 = Pattern.compile("(?s)<[ ]?input([^>]*?type=['\"]?hidden['\"]?[^>]*?)[/]?>", Pattern.CASE_INSENSITIVE);
        final Pattern intput2 = Pattern.compile("name=['\"]([^'\"]*?)['\"]", Pattern.CASE_INSENSITIVE);
        final Pattern intput3 = Pattern.compile("value=['\"]([^'\"]*?)['\"]", Pattern.CASE_INSENSITIVE);
        final Pattern intput4 = Pattern.compile("name=([^\\s]*)", Pattern.CASE_INSENSITIVE);
        final Pattern intput5 = Pattern.compile("value=([^\\s]*)", Pattern.CASE_INSENSITIVE);
        final Matcher matcher1 = intput1.matcher(data);
        Matcher matcher2;
        Matcher matcher3;
        Matcher matcher4;
        Matcher matcher5;
        final HashMap<String, String> ret = new HashMap<String, String>();
        boolean iscompl;
        while (matcher1.find()) {
            matcher2 = intput2.matcher(matcher1.group(1) + " ");
            matcher3 = intput3.matcher(matcher1.group(1) + " ");
            matcher4 = intput4.matcher(matcher1.group(1) + " ");
            matcher5 = intput5.matcher(matcher1.group(1) + " ");
            iscompl = false;
            String key, value;
            key = value = null;
            if (matcher2.find()) {
                iscompl = true;
                key = matcher2.group(1);
            } else if (matcher4.find()) {
                iscompl = true;
                key = matcher4.group(1);
            }
            if (matcher3.find() && iscompl) {
                value = matcher3.group(1);
            } else if (matcher5.find() && iscompl) {
                value = matcher5.group(1);
            } else {
                iscompl = false;
            }
            ret.put(key, value);
        }
        return ret;
    }

    private static HtmlParserCharSequence getProtocol(final HtmlParserCharSequence url) {
        if (url != null) {
            return url.group(1, HTMLParser.LINKPROTOCOL);
        }
        return null;
    }

    public static String getProtocol(final String url) {
        if (url != null) {
            final HtmlParserCharSequence ret = HTMLParser.getProtocol(new HtmlParserCharSequence(url));
            if (ret != null) {
                return ret.toString();
            }
        }
        return null;
    }

    /**
     * @author olimex FÃ¼gt Map als String mit Trennzeichen zusammen TODO: auslagern
     * @param map
     *            Map
     * @param delPair
     *            Trennzeichen zwischen Key und Value
     * @param delMap
     *            Trennzeichen zwischen Map-EintrÃ¤gen
     * @return Key-value pairs
     */
    public static String joinMap(final Map<String, String> map, final String delPair, final String delMap) {
        final StringBuilder buffer = new StringBuilder();
        boolean first = true;
        for (final Map.Entry<String, String> entry : map.entrySet()) {
            if (first) {
                first = false;
            } else {
                buffer.append(delMap);
            }
            buffer.append(entry.getKey());
            buffer.append(delPair);
            buffer.append(entry.getValue());
        }
        return buffer.toString();
    }

    private static HtmlParserCharSequence mergeUrl(final HtmlParserCharSequence baseURL, final HtmlParserCharSequence path) {
        if (path == null || baseURL == null || path.length() == 0) {
            return null;
        }
        ConcatCharSequence merged = null;
        final char first = path.charAt(0);
        if (first == '/') {
            if (path.length() > 1 && path.charAt(1) == '/') {
                /* absolut path relative to baseURL */
                HtmlParserCharSequence protocol = HTMLParser.getProtocol(baseURL);
                if (protocol != null) {
                    protocol = protocol.subSequence(0, protocol.length() - 2);
                    merged = new ConcatCharSequence(protocol, path);
                }
            } else {
                /* absolut path relative to baseURL */
                HtmlParserCharSequence base = baseURL.group(1, HTMLParser.mergePattern_Root);
                if (base != null) {
                    merged = new ConcatCharSequence(base, path);
                }
            }
        } else if (first == '.' && (path.charAt(1) == '.' || path.charAt(1) == '/')) {
            /* relative path relative to baseURL */
            HtmlParserCharSequence base = baseURL.group(1, HTMLParser.mergePattern_Path);
            if (base != null) {
                /* relative to current path */
                merged = new ConcatCharSequence(base, path);
            } else {
                base = baseURL.group(1, HTMLParser.mergePattern_Root);
                if (base != null) {
                    /* relative to root */
                    merged = new ConcatCharSequence(base, "/", path);
                }
            }
        } else if (first == '#' || first == '?') {
            /* append query/anchor to baseURL */
            HtmlParserCharSequence base = baseURL.group(1, HTMLParser.mergePattern_FileORPath);
            if (base != null) {
                /* append query/anchor to current path/file */
                merged = new ConcatCharSequence(base, path);
            } else {
                base = baseURL.group(1, HTMLParser.mergePattern_Root);
                if (base != null) {
                    /* append query/anchor to root */
                    merged = new ConcatCharSequence(base, "/", path);
                }
            }
        } else {
            /* relative path relative to baseURL */
            HtmlParserCharSequence base = baseURL.group(1, HTMLParser.mergePattern_Path);
            if (base != null) {
                /* relative to current path */
                merged = new ConcatCharSequence(base, path);
            } else {
                base = baseURL.group(1, HTMLParser.mergePattern_Root);
                if (base != null) {
                    /* relative to root */
                    merged = new ConcatCharSequence(base, "/", path);
                }
            }
        }
        if (merged != null) {
            return new HtmlParserCharSequence(merged);
        }
        return null;
    }
}
