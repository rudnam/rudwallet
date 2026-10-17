package server

import (
	"archive/zip"
	"encoding/binary"
	"errors"
	"io"
	"unicode/utf16"
)

// apkVersion is the version an APK declares in its manifest: versionCode,
// which Android compares to decide what's newer, and versionName, shown to
// people.
type apkVersion struct {
	Code int64
	Name string
}

// readAPKVersion reads the version from the APK at file. An APK's
// AndroidManifest.xml is compiled to Android's binary XML, so this reads
// just enough of that format to find the <manifest> element's attributes.
func readAPKVersion(file string) (apkVersion, error) {
	z, err := zip.OpenReader(file)
	if err != nil {
		return apkVersion{}, err
	}
	defer z.Close()
	f, err := z.Open("AndroidManifest.xml")
	if err != nil {
		return apkVersion{}, err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, 4<<20))
	if err != nil {
		return apkVersion{}, err
	}
	return parseManifestVersion(data)
}

// Android's binary XML (frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h).
const (
	resStringPoolType = 0x0001
	resXMLType        = 0x0003
	resXMLStartElem   = 0x0102
	resXMLResourceMap = 0x0180

	utf8Flag        = 1 << 8
	typeString      = 0x03
	typeIntDec      = 0x10
	typeIntHex      = 0x11
	attrVersionCode = 0x0101021b // android:versionCode
	attrVersionName = 0x0101021c // android:versionName
)

var errManifest = errors.New("not an Android binary manifest")

func parseManifestVersion(data []byte) (apkVersion, error) {
	le := binary.LittleEndian
	if len(data) < 8 || le.Uint16(data) != resXMLType {
		return apkVersion{}, errManifest
	}
	var strs []string
	var resIDs []uint32
	for off := int(le.Uint16(data[2:])); off+8 <= len(data); {
		typ, hdr, size := le.Uint16(data[off:]), int(le.Uint16(data[off+2:])), int(le.Uint32(data[off+4:]))
		if size < 8 || off+size > len(data) {
			return apkVersion{}, errManifest
		}
		chunk := data[off : off+size]
		switch typ {
		case resStringPoolType:
			var err error
			if strs, err = parseStringPool(chunk); err != nil {
				return apkVersion{}, err
			}
		case resXMLResourceMap:
			for i := hdr; i+4 <= size; i += 4 {
				resIDs = append(resIDs, le.Uint32(chunk[i:]))
			}
		case resXMLStartElem:
			// The first element is <manifest>.
			return manifestAttrs(chunk, hdr, strs, resIDs)
		}
		off += size
	}
	return apkVersion{}, errManifest
}

// manifestAttrs reads versionCode and versionName from a start element chunk.
func manifestAttrs(chunk []byte, hdr int, strs []string, resIDs []uint32) (apkVersion, error) {
	le := binary.LittleEndian
	ext := hdr // ResXMLTree_attrExt follows the node header
	if ext+20 > len(chunk) {
		return apkVersion{}, errManifest
	}
	start, size, count := int(le.Uint16(chunk[ext+8:])), int(le.Uint16(chunk[ext+10:])), int(le.Uint16(chunk[ext+12:]))
	if size < 20 {
		return apkVersion{}, errManifest
	}
	str := func(i uint32) string {
		if int(i) < len(strs) {
			return strs[i]
		}
		return ""
	}
	var v apkVersion
	for i := 0; i < count; i++ {
		a := ext + start + i*size
		if a+20 > len(chunk) {
			return apkVersion{}, errManifest
		}
		name, raw := le.Uint32(chunk[a+4:]), le.Uint32(chunk[a+8:])
		dataType, value := chunk[a+15], le.Uint32(chunk[a+16:])
		// Attributes are named by resource ID; the name string is a fallback.
		var id uint32
		if int(name) < len(resIDs) {
			id = resIDs[name]
		}
		switch {
		case id == attrVersionCode || (id == 0 && str(name) == "versionCode"):
			if dataType == typeIntDec || dataType == typeIntHex {
				v.Code = int64(value)
			}
		case id == attrVersionName || (id == 0 && str(name) == "versionName"):
			if dataType == typeString {
				v.Name = str(value)
			} else if raw != 0xffffffff {
				v.Name = str(raw)
			}
		}
	}
	if v.Code == 0 {
		return apkVersion{}, errors.New("the manifest has no versionCode")
	}
	return v, nil
}

func parseStringPool(chunk []byte) ([]string, error) {
	le := binary.LittleEndian
	if len(chunk) < 28 {
		return nil, errManifest
	}
	count, flags, start := int(le.Uint32(chunk[8:])), le.Uint32(chunk[16:]), int(le.Uint32(chunk[20:]))
	hdr := int(le.Uint16(chunk[2:]))
	if hdr+count*4 > len(chunk) || start > len(chunk) {
		return nil, errManifest
	}
	strs := make([]string, count)
	for i := range strs {
		p := start + int(le.Uint32(chunk[hdr+i*4:]))
		var ok bool
		if flags&utf8Flag != 0 {
			strs[i], ok = utf8String(chunk, p)
		} else {
			strs[i], ok = utf16String(chunk, p)
		}
		if !ok {
			return nil, errManifest
		}
	}
	return strs, nil
}

// utf8String reads a string from a UTF-8 pool: its length in UTF-16 units,
// then in bytes (each one or two bytes long), then the bytes.
func utf8String(b []byte, p int) (string, bool) {
	length := func() (int, bool) {
		if p >= len(b) {
			return 0, false
		}
		n := int(b[p])
		p++
		if n&0x80 != 0 {
			if p >= len(b) {
				return 0, false
			}
			n = (n&0x7f)<<8 | int(b[p])
			p++
		}
		return n, true
	}
	if _, ok := length(); !ok {
		return "", false
	}
	n, ok := length()
	if !ok || p+n > len(b) {
		return "", false
	}
	return string(b[p : p+n]), true
}

// utf16String reads a string from a UTF-16 pool: its length in units (one
// or two units long), then the units.
func utf16String(b []byte, p int) (string, bool) {
	le := binary.LittleEndian
	if p+2 > len(b) {
		return "", false
	}
	n := int(le.Uint16(b[p:]))
	p += 2
	if n&0x8000 != 0 {
		if p+2 > len(b) {
			return "", false
		}
		n = (n&0x7fff)<<16 | int(le.Uint16(b[p:]))
		p += 2
	}
	if p+2*n > len(b) {
		return "", false
	}
	u := make([]uint16, n)
	for i := range u {
		u[i] = le.Uint16(b[p+2*i:])
	}
	return string(utf16.Decode(u)), true
}
