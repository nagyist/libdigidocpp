/*
 * libdigidocpp
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 */

#include "SignatureTST.h"

#include "ASiC_S.h"
#include "DataFile_p.h"
#include "crypto/Digest.h"
#include "crypto/Signer.h"
#include "crypto/TS.h"
#include "crypto/X509Cert.h"
#include "util/DateTime.h"
#include "util/File.h"
#include "util/log.h"

using namespace digidoc;
using namespace std;

constexpr std::string_view DSIG_NS {"http://www.w3.org/2000/09/xmldsig#"};
constexpr XMLName DigestMethod {"DigestMethod", DSIG_NS};
constexpr XMLName DigestValue {"DigestValue", DSIG_NS};

SignatureTST::SignatureTST(const string &data, ASiC_S *asicSDoc)
    : asicSDoc(asicSDoc)
    , timestampToken(make_unique<TS>((const unsigned char*)data.data(), data.size()))
{}

SignatureTST::SignatureTST(string current, XMLDocument &&xml, const string &data, ASiC_S *asicSDoc)
    : SignatureTST(data, asicSDoc)
{
    file = std::move(current);
    doc = std::move(xml);
}

SignatureTST::SignatureTST(ASiC_S *asicSDoc, Signer *signer)
    : asicSDoc(asicSDoc)
{
    auto *dataFile = static_cast<DataFilePrivate*>(asicSDoc->dataFiles().front());
    Digest digest;
    dataFile->digest(digest);
    timestampToken = make_unique<TS>(digest, signer->userAgent());
}

SignatureTST::~SignatureTST() = default;

X509Cert SignatureTST::TimeStampCertificate() const
{
    return timestampToken->cert();
}

string SignatureTST::TimeStampTime() const
{
    return util::date::to_string(timestampToken->time());
}

string SignatureTST::trustedSigningTime() const
{
    return TimeStampTime();
}

// DSig properties
string SignatureTST::id() const
{
    return timestampToken->serial();
}

string SignatureTST::claimedSigningTime() const
{
    return TimeStampTime();
}

X509Cert SignatureTST::signingCertificate() const
{
    return TimeStampCertificate();
}

string SignatureTST::signatureMethod() const
{
    return timestampToken->digestMethod();
}

void SignatureTST::validate() const
{
    Exception exception(EXCEPTION_PARAMS("Timestamp validation."));

    if(!timestampToken)
    {
        EXCEPTION_ADD(exception, "Failed to parse timestamp token.");
        throw exception;
    }
    try
    {
        timestampToken->verify(dataToSign());
        if(auto digestMethod = signatureMethod();
            !Exception::hasWarningIgnore(Exception::ReferenceDigestWeak) &&
            Digest::isWeakDigest(digestMethod))
        {
            Exception e(EXCEPTION_PARAMS("TimeStamp '%s' digest weak", digestMethod.c_str()));
            e.setCode(Exception::ReferenceDigestWeak);
            exception.addCause(e);
        }
        if(doc)
        {
            DataFile *file = asicSDoc->dataFiles().front();
            for(auto ref = doc/"DataObjectReference"; ref; ref++)
            {
                string_view method = (ref/DigestMethod)["Algorithm"];
                auto uri = util::File::fromUriPath(ref["URI"]);
                vector<unsigned char> digest = file->fileName() == uri ?
                    dynamic_cast<const DataFilePrivate*>(file)->calcDigest(string(method)) :
                    asicSDoc->fileDigest(uri, method).result();
                if(vector<unsigned char> digestValue = ref/DigestValue; digest != digestValue)
                    THROW("Reference %s digest does not match", uri.c_str());
            }
        }
    }
    catch (const Exception& e)
    {
        exception.addCause(e);
    }

    if(!exception.causes().empty())
        throw exception;
}

std::vector<unsigned char> SignatureTST::dataToSign() const
{
    if(!file.empty())
        return asicSDoc->fileDigest(file, signatureMethod()).result();
    return asicSDoc->dataFiles().front()->calcDigest(signatureMethod());
}

vector<unsigned char> SignatureTST::messageImprint() const
{
    return timestampToken->messageImprint();
}

void SignatureTST::setSignatureValue(const std::vector<unsigned char> & /*signatureValue*/)
{
    THROW("Not implemented.");
}

// Xades properties
string SignatureTST::profile() const
{
    return string(ASiC_S::ASIC_TST_PROFILE);
}

std::vector<unsigned char> SignatureTST::save() const
{
    return *timestampToken;
}
