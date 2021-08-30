DESCRIPTION = "AOS fierwall CNI plugin"

LICENSE = "Apache-2.0"

GO_IMPORT = "aos_cni_firewall"
LIC_FILES_CHKSUM = "file://src/${GO_IMPORT}/LICENSE;md5=3b83ef96387f14655fc854ddc3c6bd57"

BRANCH = "master"
SRCREV = "${AUTOREV}"
SRC_URI = "git://git@gitpct.epam.com/epmd-aepr/${GO_IMPORT}.git;branch=${BRANCH};protocol=ssh"

inherit go
inherit goarch

# embed version
GO_LDFLAGS += '-ldflags="-X github.com/containernetworking/plugins/pkg/utils/buildversion.BuildVersion=`git --git-dir=${S}/src/${GO_IMPORT}/.git describe --tags --always` ${GO_RPATH} ${GO_LINKMODE} -extldflags '${GO_EXTLDFLAGS}'"'

do_compile() {
    cd ${S}/src/${GO_IMPORT}/
    ${GO} build -o ${B}/bin/aos-firewall ./plugins/meta/aos-firewall
}

do_install() {
    localbindir="${libexecdir}/cni/"

    install -d ${D}${localbindir}
    install -m 755 ${B}/bin/aos-firewall ${D}${localbindir}
}

FILES_${PN} = "${libexecdir}/cni/*"