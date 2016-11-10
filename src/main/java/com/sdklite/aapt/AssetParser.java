package com.sdklite.aapt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sdklite.sed.StreamEditor;

/**
 * The Android asset parser
 * 
 * @author johnsonlee
 *
 */
final class AssetParser extends StreamEditor {

    public AssetParser(final File file) throws FileNotFoundException {
        super(file, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Parses from the beginning of file
     * 
     * @return a resource chunk
     * @throws IOException
     *             if error occurred
     */
    public Chunk parse() throws IOException {
        seek(0);

        switch (peekShort()) {
        case ChunkType.STRING_POOL:
            return parseStringPool();
        case ChunkType.TABLE:
            return parseResourceTable();
        case ChunkType.XML:
            return parseXml();
        default:
            throw new AaptException(String.format("Unsupported chunk type 0x%04x", ChunkType.NULL));
        }
    }

    public short expectChunkTypes(final short... expected) throws IOException {
        final short actural = peekShort();

        for (int i = 0, n = expected.length; i < n; i++) {
            if (actural == expected[i]) {
                return actural;
            }
        }

        throw new AaptException(String.format("Expect chunk type %s, but 0x%04x found", Internal.hexlify(expected), actural));
    }

    public <T extends ChunkHeader> T parseChunkHeader(final T chunk) throws IOException {
        final short type = readShort();
        if (chunk.type != type) {
            throw new AaptException(String.format("Expect chunk type 0x%04x, but 0x%04x found", chunk.type, type));
        }

        chunk.headerSize = readShort();
        if (chunk.headerSize < ChunkHeader.MIN_HEADER_SIZE) {
            throw new AaptException(String.format("Chunk header size at least %d bytes", ChunkHeader.MIN_HEADER_SIZE));
        }

        chunk.size = readInt();
        if (chunk.size < ChunkHeader.MIN_HEADER_SIZE || chunk.size < chunk.headerSize) {
            throw new AaptException(String.format("Chunk size at least %d bytes", ChunkHeader.MIN_HEADER_SIZE));
        }

        return chunk;
    }

    public boolean hasMoreChunks() throws IOException {
        if (remaining() < Chunk.MIN_HEADER_SIZE) {
            return false;
        }

        final long p = tell();

        try {
            return ChunkType.ALL_TYPES.contains(readShort());
        } finally {
            seek(p);
        }
    }

    public ChunkHeader parseChunkHeader() throws IOException {
        return parseChunkHeader(new ChunkHeader(peekShort()));
    }

    public ResourceTable parseResourceTable() throws IOException {
        final long p = tell();

        return new ResourceTable() {

            private final StringPool pool;

            {
                parseChunkHeader(this);

                final int npkg = readInt();
                if (npkg <= 0) {
                    throw new AaptException("No packages found");
                }

                this.pool = parseStringPool();

                for (int i = 0; i < npkg; i++) {
                    final ResourceTable.Package pkg = parsePackage(this);
                    final ResourceTable.PackageGroup group;

                    int index = this.packageMap[pkg.id];
                    if (index == 0) {
                        index = this.packageGroups.size() + 1;
                        group = new ResourceTable.PackageGroup(this, pkg.name, pkg.id);
                        this.packageGroups.add(group);
                        this.packageMap[pkg.id] = (byte) index;

                        for (int j = 0, n = this.packageGroups.size(); j < n; j++) {
                            this.packageGroups.get(j).dynamicRefTale.addMapping(pkg.name, (byte) pkg.id);
                        }
                    } else {
                        group = this.packageGroups.get(index - 1);
                        if (null == group) {
                            throw new AaptException("Package group not found");
                        }
                    }

                    group.packages.add(pkg);

                    while (tell() - p < this.size) {
                        switch (expectChunkTypes(TABLE_TYPE, TABLE_TYPE_SPEC, TABLE_LIBRARY)) {
                        case TABLE_TYPE_SPEC: {
                            final ResourceTable.TypeSpec spec = parseResourceTableTypeSpec(pkg);
                            pkg.specs.add(spec);
                            break;
                        }
                        case TABLE_TYPE: {
                            final ResourceTable.Type type = parseResourceTableType(pkg);
                            pkg.specs.get(type.id - 1).configs.add(type);
                            break;
                        }
                        case TABLE_LIBRARY:
                            this.libraries.add(parseResourceTableLibrary(pkg));
                            break;
                        }
                    }
                }
            }

            @Override
            public StringPool getStringPool() {
                return this.pool;
            }
        };
    }

    public ResourceTable.Package parsePackage(final ResourceTable table) throws IOException {
        return table.new Package() {

            private final StringPool typePool;
            private final StringPool keyPool;

            {
                parseChunkHeader(this);

                this.id = readInt();
                this.name = parsePackageName();
                this.typeStrings = readInt();
                this.lastPublicType = readInt();
                this.keyStrings = readInt();
                this.lastPublicKey = readInt();

                if (this.headerSize == ResourceTable.Package.HEADER_SIZE) {
                    this.typeIdOffset = readInt();
                } else {
                    this.typeIdOffset = 0;
                }

                if (this.typeStrings != 0) {
                    this.typePool = parseStringPool();
                } else {
                    this.typePool = null;
                }

                if (this.keyStrings != 0) {
                    this.keyPool = parseStringPool();
                } else {
                    this.keyPool = null;
                }
            }

            @Override
            public StringPool getTypeStringPool() {
                return this.typePool;
            }

            @Override
            public StringPool getKeyStringPool() {
                return this.keyPool;
            }
        };
    }

    public ResourceTable.TypeSpec parseResourceTableTypeSpec(final ResourceTable.Package pkg) throws IOException {
        final ResourceTable table = pkg.getResourceTable();
        return table.new TypeSpec() {
            {
                parseChunkHeader(this);

                this.id = readByte();
                if (this.id < 1) {
                    throw new AaptException(String.format("Invalid type specification id %d", this.id));
                }

                this.res0 = readByte();
                if (0 != res0) {
                    throw new AaptException("res0 expected to be zero");
                }

                this.res1 = readShort();
                if (0 != this.res1) {
                    throw new AaptException("res1 expected to be zero");
                }

                for (int i = 0, entryCount = readInt(); i < entryCount; i++) {
                    this.flags.add(readInt());
                }
            }

            @Override
            public ResourceTable.Package getPackage() {
                return pkg;
            }
        };
    }

    public ResourceTable.Type parseResourceTableType(final ResourceTable.Package pkg) throws IOException {
        final long p = tell();
        final ResourceTable table = pkg.getResourceTable();

        return table.new Type() {
            {
                parseChunkHeader(this);

                this.id = readByte();
                if (this.id < 1) {
                    throw new AaptException(String.format("Invalid type id %d", this.id));
                }

                this.res0 = readByte();
                if (0 != res0) {
                    throw new AaptException("res0 expected to be zero");
                }

                this.res1 = readShort();
                if (0 != this.res1) {
                    throw new AaptException("res1 expected to be zero");
                }

                final int entryCount = readInt();
                this.entriesStart = readInt();
                this.config.size = readInt();
                this.config.imsi.mcc = readShort();
                this.config.imsi.mnc = readShort();
                this.config.locale.language[1] = readByte();
                this.config.locale.language[0] = readByte();
                this.config.locale.country[1] = readByte();
                this.config.locale.country[0] = readByte();
                this.config.screenType.orientation = readByte();
                this.config.screenType.touchscreen = readByte();
                this.config.screenType.density = readShort();
                this.config.input.keyboard = readByte();
                this.config.input.navigation = readByte();
                this.config.input.flags = readByte();
                this.config.input.pad0 = readByte();
                this.config.screenSize.width = readShort();
                this.config.screenSize.height = readShort();
                this.config.version.sdk = readShort();
                this.config.version.minor = readShort();

                if (this.config.size >= 32) {
                    this.config.screenConfig.layout = readByte();
                    this.config.screenConfig.uiMode = readByte();
                    this.config.screenConfig.smallestWidthDp = readShort();
                }

                if (this.config.size >= 36) {
                    this.config.screenSizeDp.width = readShort();
                    this.config.screenSizeDp.height = readShort();
                }

                if (this.config.size >= 48) {
                    this.config.localeScript[0] = readByte();
                    this.config.localeScript[1] = readByte();
                    this.config.localeScript[2] = readByte();
                    this.config.localeScript[3] = readByte();
                    this.config.localeVariant[0] = readByte();
                    this.config.localeVariant[1] = readByte();
                    this.config.localeVariant[2] = readByte();
                    this.config.localeVariant[3] = readByte();
                    this.config.localeVariant[4] = readByte();
                    this.config.localeVariant[5] = readByte();
                    this.config.localeVariant[6] = readByte();
                    this.config.localeVariant[7] = readByte();
                }

                if (this.config.size >= 52) {
                    this.config.screenConfig2.layout = readByte();
                    this.config.screenConfig2.pad1 = readByte();
                    this.config.screenConfig2.pad2 = readShort();
                }

                seek(p + this.headerSize);

                for (int i = 0; i < entryCount; i++) {
                    this.entries.add(new IndexedEntry<ResourceTable.Entry>(readInt(), null));
                }

                final long entriesStart = p + this.entriesStart;

                for (int i = 0; i < entryCount; i++) {
                    final IndexedEntry<ResourceTable.Entry> entry = this.entries.get(i);

                    if (ResourceTable.Entry.NO_ENTRY != entry.index) {
                        seek(entriesStart + entry.index);
                        entry.value = parseResourceTableEntry();
                    }
                }

                seek(p + this.size);
            }

            @Override
            public ResourceTable.Package getPackage() {
                return pkg;
            }

            @Override
            public ResourceTable.Config getConfig() {
                return this.config;
            }
        };
    }

    public ResourceTable.Entry parseResourceTableEntry() throws IOException {
        final ResourceTable.Entry entry = new ResourceTable.Entry() {
            {
                this.size = readShort();
                this.flags = readShort();
                this.key = readInt();
            }
        };

        return (entry.flags & ResourceTable.Entry.FLAG_COMPLEX) != 0 ? parseResourceTableMapEntry(entry)
                : parseResourceTableValueEntry(entry);
    }

    public ResourceTable.MapEntry parseResourceTableMapEntry(final ResourceTable.Entry base) throws IOException {
        final ResourceTable.MapEntry entry = new ResourceTable.MapEntry(base);
        entry.parent = readInt();

        for (int i = 0, count = readInt(); i < count; i++) {
            entry.values.add(parseResourceTableMap());
        }

        return entry;
    }

    public ResourceTable.Map parseResourceTableMap() throws IOException {
        final ResourceTable.Map map = new ResourceTable.Map();
        map.name = readInt();
        parseResourceValue(map.value);
        return map;
    }

    public ResourceValue parseResourceValue() throws IOException {
        return parseResourceValue(new ResourceValue());
    }

    public ResourceValue parseResourceValue(final ResourceValue value) throws IOException {
        value.size = readShort();
        value.res0 = readByte();
        value.dataType = readByte();
        value.data = readInt();
        return value;
    }

    public ResourceTable.ValueEntry parseResourceTableValueEntry(final ResourceTable.Entry base) throws IOException {
        final ResourceTable.ValueEntry entry = new ResourceTable.ValueEntry(base);
        parseResourceValue(entry.value);
        return entry;
    }

    public ResourceTable.Library parseResourceTableLibrary(final ResourceTable.Package pkg) throws IOException {
        final ResourceTable table = pkg.getResourceTable();
        return table.new Library() {
            {
                parseChunkHeader(this);

                for (int i = 0, count = readInt(); i < count; i++) {
                    this.entries.add(new IndexedEntry<String>(readInt(), parsePackageName()));
                }
            }
        };
    }

    public String parsePackageName() throws IOException {
        final long p = tell();
        final StringBuilder name = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            final char c = readChar();
            if (c == 0) {
                break;
            }

            name.append(c);
        }

        seek(p + 256);
        return name.toString();
    }

    public Xml parseXml() throws IOException {
        final Xml xml = parseChunkHeader(new Xml());

        while (hasRemaining()) {
            switch (expectChunkTypes(ChunkType.STRING_POOL, ChunkType.XML_RESOURCE_MAP, ChunkType.XML_CDATA, ChunkType.XML_END_ELEMENT, ChunkType.XML_END_NAMESPACE, ChunkType.XML_START_ELEMENT, ChunkType.XML_START_NAMESPACE)) {
            case ChunkType.STRING_POOL:
                xml.pool = parseStringPool();
                break;
            case ChunkType.XML_RESOURCE_MAP:
                xml.resources = parseXmlResourceMap(xml);
                break;
            case ChunkType.XML_CDATA:
                xml.chunks.add(parseXmlCharData(xml));
                break;
            case ChunkType.XML_END_ELEMENT:
                xml.chunks.add(parseXmlEndElement(xml));
                break;
            case ChunkType.XML_END_NAMESPACE:
                xml.chunks.add(parseXmlNamespace(xml.new Namespace(ChunkType.XML_END_NAMESPACE)));
                break;
            case ChunkType.XML_START_ELEMENT:
                xml.chunks.add(parseXmlStartElement(xml));
                break;
            case ChunkType.XML_START_NAMESPACE:
                xml.chunks.add(parseXmlNamespace(xml.new Namespace(ChunkType.XML_START_NAMESPACE)));
                break;
            }
        }

        return xml;
    }

    public <T extends Xml.Node> T parseXmlNode(final T node) throws IOException {
        parseChunkHeader(node);
        node.lineNumber = readInt();
        node.commentIndex = readInt();
        return node;
    }

    public Xml.Namespace parseXmlNamespace(final Xml.Namespace namespace) throws IOException {
        parseXmlNode(namespace);
        namespace.prefix = readInt();
        namespace.uri = readInt();
        return namespace;
    }

    public <T extends Xml.Element> T parseXmlElement(final T element) throws IOException {
        parseXmlNode(element);
        element.ns = readInt();
        element.name = readInt();
        return element;
    }

    public Xml.Element parseXmlStartElement(final Xml xml) throws IOException {
        final Xml.Element element = parseXmlElement(xml.new Element(ChunkType.XML_START_ELEMENT));
        element.attributeStart = readShort();
        element.attributeSize = readShort();
        final short attributeCount = readShort();
        element.idIndex = readShort();
        element.classIndex = readShort();
        element.styleIndex = readShort();

        for (int i = 0; i < attributeCount; i++) {
            element.attributes.add(parseXmlElementAttribute(element));
        }

        return element;
    }

    public Xml.Element parseXmlEndElement(final Xml xml) throws IOException {
        return parseXmlElement(xml.new Element(ChunkType.XML_END_ELEMENT));
    }

    public Xml.CharData parseXmlCharData(final Xml xml) throws IOException {
        final Xml.CharData cdata = parseXmlNode(xml.new CharData());
        cdata.data = readInt();
        parseResourceValue(cdata.typedData);
        return cdata;
    }

    public Xml.ResourceMap parseXmlResourceMap(final Xml xml) throws IOException {
        final Xml.ResourceMap resourceMap = parseChunkHeader(xml.new ResourceMap());
        for (int i = 0, n = (resourceMap.size - resourceMap.headerSize) / 4; i < n; i++) {
            resourceMap.ids.add(readInt());
        }
        return resourceMap;
    }

    public Xml.Attribute parseXmlElementAttribute(final Xml.Element element) throws IOException {
        final Xml.Attribute attr = element.getDocument().new Attribute();
        attr.ns = readInt();
        attr.name = readInt();
        attr.rawValue = readInt();
        parseResourceValue(attr.typedValue);
        return attr;
    }

    public StringPool parseStringPool() throws IOException {
        final long p = tell();

        return new StringPool() {
            {
                parseChunkHeader(this);

                final int stringCount = readInt();
                final int styleCount = readInt();

                this.flags = readInt();
                this.stringsStart = readInt();
                this.stylesStart = readInt();

                for (int i = 0; i < stringCount; i++) {
                    this.strings.add(new IndexedEntry<String>(readInt(), null));
                }

                for (int i = 0; i < styleCount; i++) {
                    this.styles.add(new IndexedEntry<StringPool.Style>(readInt(), new StringPool.Style()));
                }

                for (int i = 0; i < stringCount; i++) {
                    final IndexedEntry<String> entry = this.strings.get(i);
                    seek(p + this.stringsStart + entry.index);
                    entry.value = isUTF8() ? parseUtf8String() : parseUtf16String();
                }

                for (int i = 0; i < styleCount; i++) {
                    final IndexedEntry<StringPool.Style> entry = this.styles.get(i);
                    seek(p + this.stylesStart + entry.index);

                    for (long p = tell(); StringPool.Span.END != readInt(); p = tell()) {
                        seek(p);

                        final StringPool.Span span = parseStringPoolSpan();
                        entry.value.add(span);
                        if (span.name == StringPool.Span.END) {
                            break;
                        }
                    }
                }

                seek(p + this.size);
            }
        };

    }

    public StringPool.Span parseStringPoolSpan() throws IOException {
        final StringPool.Span span = new StringPool.Span();
        span.name = readInt() & 0xffffffff;
        if (span.name != StringPool.Span.END) {
            span.firstChar = readInt();
            span.lastChar = readInt();
        }

        return span;
    }

    public String parseUtf8String() throws IOException {
        int nchars = readByte() & 0xff;
        if ((nchars & 0x80) != 0) {
            nchars = ((nchars & 0x7f) << 8) | (readByte() & 0xff);
        }

        int nbytes = readByte() & 0xff;
        if ((nbytes & 0x80) != 0) {
            nbytes = ((nbytes & 0x7f) << 8) | (readByte() & 0xff);
        }

        final ByteBuffer str = ByteBuffer.allocate(nbytes).order(ByteOrder.LITTLE_ENDIAN);
        read(str);
        str.rewind();

        final int terminator = readByte();
        if (0 != terminator) {
            throw new AaptException(String.format("Zero terminator expected at position %d, but 0x%02x found", tell() - 1, terminator));
        }

        return StandardCharsets.UTF_8.decode(str).toString();
    }

    public String parseUtf16String() throws IOException {
        int nchars = readShort();
        if ((nchars & 0x8000) != 0) {
            nchars = ((nchars & 0x7fff) << 16) | (readShort() & 0xffff);
        }

        final ByteBuffer data = ByteBuffer.allocate(nchars * 2).order(ByteOrder.LITTLE_ENDIAN);
        read(data);
        data.rewind();

        final int terminator = readChar();
        if (0 != terminator) {
            throw new AaptException(String.format("Zero terminator expected at position %d, buf 0x%04x found", (tell() - 2), terminator));
        }

        return StandardCharsets.UTF_16LE.decode(data).toString();
    }

    public void rewritePackageId(final int pp, final Map<Integer, Integer> idMap) throws IOException {
        final long pos = tell();
        int id = readInt();

        if ((id >> 24) != Constants.APP_PACKAGE_ID) {
            return;
        }

        if (null != idMap && idMap.containsKey(id)) {
            id = idMap.get(id);
        } else {
            id = ((pp & 0xff) << 24) | (id & 0x00ffffff);
        }

        seek(pos);
        writeInt(id);
    }
    
}
