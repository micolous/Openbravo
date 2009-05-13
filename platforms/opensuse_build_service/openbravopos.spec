#
# spec file for package openbravopos
#
# Copyright (c) 2008 Jordi Mas i Hernandez
# This file and all modifications and additions to the pristine
# package are under the same license as the package itself.
#
# Please submit bugfixes or comments via http://bugs.opensuse.org/
#


Name:           openbravopos
Summary:        Java based POS system
License:        GPL
Version:        2.10
Release:        73
URL:            http://www.openbravo.com
Group:          Productivity
Source:         %{name}-%{version}-bin.zip
Autoreqprov:    on
PreReq:         filesystem
Requires:       jre >= 1.5.0
BuildRequires:  unzip
BuildRequires:  desktop-file-utils
BuildArch:      noarch
BuildRoot:      %{_tmppath}/%{name}-%{version}-build

%if 0%{?suse_version}
BuildRequires: update-desktop-files
%endif

%description
Openbravo POS is a point of sale application designed for touch screens, supports ESC/POS ticket printers, customer displays and barcode scanners. It is multiuser providing product entry forms, reports and charts.

%prep
%setup -q

%build
# Just packing the binary files for now

%install
# jars
install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/lib
install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/lib/Linux
install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/lib/Linux/i686-unknown-linux-gnu
install -m 644 lib/*.jar $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/lib
install -m 644 lib/Linux/i686-unknown-linux-gnu/*.so $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/lib/Linux/i686-unknown-linux-gnu/

install -m 644 openbravopos.jar $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/openbravopos.jar

install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/locales
install -m 644 locales/* $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/locales

install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/reports
install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/reports/com
install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/reports/com/openbravo
install -d -m 755 $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/reports/com/openbravo/reports
install -m 644 reports/com/openbravo/reports/* $RPM_BUILD_ROOT%{_bindir}/%{name}-%{version}/reports/com/openbravo/reports

# start script
install -m 755 start.sh $RPM_BUILD_ROOT%{_bindir}/openbravopos

# desktop menu

%if 0%{?suse_version}
%suse_update_desktop_file -i %{name}
%endif

%if 0%{?fedora_version}
desktop-file-install $RPM_BUILD_ROOT%{_bindir}/%{name}.desktop
%endif

%files
%defattr(-,root,root)
%dir %{_bindir}/%{name}-%{version}
%dir %{_bindir}/%{name}-%{version}/lib
%dir %{_bindir}/%{name}-%{version}/lib/Linux
%dir %{_bindir}/%{name}-%{version}/lib/Linux/i686-unknown-linux-gnu
%dir %{_bindir}/%{name}-%{version}/locales
%dir %{_bindir}/%{name}-%{version}/reports
%dir %{_bindir}/%{name}-%{version}/reports/com
%dir %{_bindir}/%{name}-%{version}/reports/com/openbravo
%dir %{_bindir}/%{name}-%{version}/reports/com/openbravo/reports
%{_bindir}/%{name}-%{version}/%{name}.jar
#openbravopos executable script
%{_bindir}/openbravopos
%{_bindir}/%{name}-%{version}/lib/*
%{_bindir}/%{name}-%{version}/locales/*
%{_bindir}/%{name}-%{version}/reports/*

%if 0%{?suse_version}
%{_datadir}/applications/%{name}.desktop
%endif

%changelog
* Mon Jul 16 2008 Jordi Mas <jmas@openbravo.com>
- initial spec file

