package org.openbravo.service.pos;

import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.util.CheckException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.dal.xml.EntityXMLConverter;
import org.openbravo.dal.xml.ModelXMLConverter;
import org.openbravo.dal.xml.XMLUtil;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.ApprovedVendor;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.dataimport.Order;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.onhandquantity.StorageDetail;
import org.openbravo.model.pos.ExternalPOS;
import org.openbravo.model.pos.ExternalPOSProduct;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.service.web.ResourceNotFoundException;
import org.openbravo.service.web.WebService;
import org.openbravo.service.web.WebServiceUtil;

/**
 * Openbravo POS and ERP Synchronization WebService
 * 
 * @author mirurita
 */

public class POSWebService implements WebService {

    private static final long serialVersionUID = 1L;
    private static List<String> filterBP;
    private static List<String> filterProduct;
    private static List<String> filterWarehouse;
    private static List<String> filterProductCategory;

    public void doGet(String path, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        final String segment = WebServiceUtil.getInstance().getFirstSegment(
                path);

        Document doc = null;
        if (segment == null || segment.length() == 0) {
            doc = ModelXMLConverter.getInstance().getEntitiesAsXML();
        } else {
            final String entityName = segment;
            // Parameters
            final String erp_id = request.getParameter("erp.id");
            final String erp_org = request.getParameter("erp.org");
            final String erp_pos = request.getParameter("erp.pos");
            final String insertFlag = request.getParameter("insertFlag");

            try {
                ModelProvider.getInstance().getEntity(entityName);
            } catch (final CheckException ce) {
                throw new ResourceNotFoundException("Resource " + entityName
                        + " not found", ce);
            }

            // Where clause
            final String where = request.getParameter("where");

            // Execute Query
            final OBQuery<BaseOBObject> obq = OBDal.getInstance().createQuery(
                    entityName, where);
            obq.setFilterOnReadableClients(false);
            obq.setFilterOnReadableOrganization(false);

            // Get xml
            final EntityXMLConverter exc = EntityXMLConverter.newInstance();
            exc.setOptionEmbedChildren(true);
            exc.setOptionIncludeChildren(true);
            exc.setOptionIncludeReferenced(false);
            exc.setOptionExportClientOrganizationReferences(true);
            StringWriter sw = new StringWriter();
            exc.setOutput(sw);
            exc.process(obq.list());
            final String xml = sw.toString();
            doc = DocumentHelper.parseText(xml);

            // PRODUCTS
            if (entityName.equals("ExternalPOS") && erp_pos != null) {
                final Map<String, String> lData = processExternalPOS(doc,
                        erp_pos);
                String whereClauseProduct = lData.get("whereProduct");
                if (whereClauseProduct == null) {
                    whereClauseProduct = "client.id=" + erp_id
                            + " and organization.id in ('0', " + erp_org + ")";
                }
                final OBQuery<BaseOBObject> obq2 = OBDal.getInstance()
                        .createQuery("Product", whereClauseProduct);
                obq2.setFilterOnReadableClients(false);
                obq2.setFilterOnReadableOrganization(false);
                sw = new StringWriter();
                exc.setOutput(sw);
                exc.process(obq2.list());
                final String xml2 = sw.toString();
                doc = DocumentHelper.parseText(xml2);

                final Map<String, String> pBuy = priceBuyProductByPriceList(obq2);
                final Map<String, String> pSell = priceSellProductByPriceList(
                        lData.get("priceListId"), obq2);

                final List<Element> lNodes = doc
                        .selectNodes("/ob:Openbravo/Product");
                for (final Element e : lNodes) {
                    final String sid = e.elementText("id");
                    final String defSell = (pSell.get(sid) == null) ? "0"
                            : pSell.get(sid);
                    final String defBuy = (pBuy.get(sid) == null) ? "0" : pBuy
                            .get(sid);
                    e.addElement("priceSell").addAttribute("price", defSell);
                    e.addElement("priceBuy").addAttribute("price", defBuy);
                    e.addElement("warehouse").addAttribute("id",
                            lData.get("warehouseId"));
                }
                setFilterProduct();
                XMLDocumentFilter(doc, "Product", filterProduct);
            }
            // PRODUCT CATEGORY
            else if (entityName.equals("ProductCategory") && (erp_id != null)) {
                setFilterProductCategory();
                XMLDocumentFilter(doc, "ProductCategory", filterProductCategory);
            }
            // BUSINESSPARTNER
            else if (segment.equals("BusinessPartner") && (erp_id != null)) {
                setFilterBusinessPartner();
                doc = getBusinessPartnerInfo(doc);
                XMLDocumentFilter(doc, "BusinessPartner", filterBP);

            }
            // WAREHOUSE
            else if (segment.equals("Warehouse") && (erp_id != null)) {
                setFilterWarehouse();
                XMLDocumentFilter(doc, "Warehouse", filterWarehouse);
            }
            // INVENTORY
            else if (segment.equals("MaterialMgmtStorageDetail")
                    && (erp_pos != null)) {
                doc = processInventory(erp_pos, erp_id, erp_org, exc);
            }

            // INSERT ORDERS
            if (!obq.list().isEmpty() && insertFlag != null) {
                doChangeAction(path, request, response, obq);
            }

        }

        response.setContentType("text/xml");
        response.setCharacterEncoding("utf-8");
        final String xml = XMLUtil.getInstance().toString(doc);
        final Writer w = response.getWriter();
        w.write(xml);
        w.close();

    }

    protected void doChangeAction(String path, HttpServletRequest request,
            HttpServletResponse response, OBQuery<BaseOBObject> obq) {
        try {
            final Order iOrder = createImportOrder(request, obq);
            OBDal.getInstance().save(iOrder);
        } catch (final Exception e) {
            throw new OBException("Error saving Entity into database");
        }
    }

    private Order createImportOrder(HttpServletRequest request,
            OBQuery<BaseOBObject> obq) {
        final String ti_id = request.getParameter("ti_id");
        final String ti_type = request.getParameter("ti_type");
        final String ti_date = request.getParameter("ti_date");
        final String bp_id = request.getParameter("bp_id");
        final String line_product = request.getParameter("line_product");
        final String line_units = request.getParameter("line_units");
        final String line_price = request.getParameter("line_price");
        final String tax_id = request.getParameter("tax_id");
        final String payment_total = request.getParameter("payment_total");
        final String erp_org = request.getParameter("erp.org");

        final Order iOrder = new Order();

        final ExternalPOS extPos = (ExternalPOS) obq.list().get(0);

        // Client
        iOrder.setClient(extPos.getClient());

        // Organization
        final Organization syncOrgSel = (Organization) OBDal.getInstance().get(
                "Organization", erp_org);
        iOrder.setOrganization(syncOrgSel);

        // Sales representative
        iOrder.setSalesRepresentative(extPos.getSalesRepresentative());

        // Warehouse
        iOrder.setWarehouse(extPos.getWarehouse());

        // Price list
        iOrder.setPriceList(extPos.getPriceList());

        // Currency
        iOrder.setCurrency(extPos.getPriceList().getCurrency());

        // Shipping company
        iOrder.setShippingCompany(extPos.getShippingCompany());

        // Business Partner
        if (bp_id == null) {
            final BusinessPartner syncBp = extPos.getBusinessPartner();
            iOrder.setBusinessPartner(syncBp);
            final Location syncBpLoc = (syncBp.getBusinessPartnerLocationList()
                    .isEmpty()) ? null : syncBp
                    .getBusinessPartnerLocationList().get(0);

            iOrder.setPartnerAddress(syncBpLoc);
            // Payment Term
            iOrder.setPaymentTerms(syncBp.getPaymentTerms());
        } else {
            final BusinessPartner syncSelBp = (BusinessPartner) OBDal
                    .getInstance().get("BusinessPartner", bp_id);
            iOrder.setBusinessPartner(syncSelBp);
            final Location syncBpLoc = (syncSelBp
                    .getBusinessPartnerLocationList().isEmpty()) ? null
                    : syncSelBp.getBusinessPartnerLocationList().get(0);
            iOrder.setPartnerAddress(syncBpLoc);
        }

        // Document type
        iOrder.setDocumentType(extPos.getDocumentType());

        // Document number
        iOrder.setDocumentNo(ti_id + "." + ti_type);

        // Document name
        iOrder.setDocumentTypeName(extPos.getDocumentType().getName());

        // Product
        final Product pro = (Product) OBDal.getInstance().get("Product",
                line_product);
        iOrder.setProduct(pro);

        // Taxes
        final TaxRate tax = (TaxRate) OBDal.getInstance().get(
                "FinancialMgmtTaxRate", tax_id);
        iOrder.setTax(tax);

        // Quantity ordered
        iOrder.setOrderedQuantity(BigDecimal
                .valueOf(Double.valueOf(line_units)));

        // Price of product
        iOrder.setUnitPrice(BigDecimal.valueOf(Double.valueOf(line_price)));

        // Data ordered
        final DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        Date date = null;
        try {
            date = formatter.parse(ti_date);
        } catch (final ParseException e) {
            throw new OBException("Error parsing date " + ti_date);
        }

        iOrder.setOrderDate(date);

        // Payment amount1
        iOrder.setPaymentamount1(Float.valueOf(payment_total));

        // Payment rules
        iOrder.setPaymentrule1(null);
        iOrder.setPaymentrule2(null);

        return iOrder;
    }

    /**
     * Get sell price of products in a specific PriceList
     * 
     * @param priceListID
     *            the warehouse id where products are stored
     * 
     */
    private Map<String, String> priceSellProductByPriceList(String priceListID,
            OBQuery<BaseOBObject> proQuery) {
        final String entityName = "PricingPriceList";
        final ArrayList<String> listVer = new ArrayList<String>();
        final Map<String, String> productIDandPriceSell = new HashMap<String, String>();
        // Get PriceList with priceListID
        final BaseOBObject bobo = OBDal.getInstance().get(entityName,
                priceListID);
        if (bobo == null) {
            throw new ResourceNotFoundException("No resource found for entity "
                    + entityName + " using id " + priceListID);
        }
        // Get PriceListVersions of the PriceList with priceListID
        final List<PriceListVersion> pricelvl = (List<PriceListVersion>) bobo
                .get("pricingPriceListVersionList");
        for (final PriceListVersion plv : pricelvl) {
            listVer.add(plv.getId());
        }

        // Get sell price of products which belong to a ProductListVersion into
        // a selected PriceList
        final List<BaseOBObject> lbobo = proQuery.list();
        for (final BaseOBObject b : lbobo) {
            final List<ProductPrice> pppl = (List<ProductPrice>) b
                    .get("pricingProductPriceList");

            for (final ProductPrice rppp : pppl) {
                if (listVer.contains(rppp.getPriceListVersion().getId())) {
                    productIDandPriceSell.put(b.getId().toString(), rppp
                            .getListPrice().toString());
                }
            }

        }
        return productIDandPriceSell;
    }

    /**
     * Get buy price of products in a specific PriceList
     * 
     */
    private Map<String, String> priceBuyProductByPriceList(
            OBQuery<BaseOBObject> proQuery) {
        final Map<String, String> productIDandPriceBuy = new HashMap<String, String>();

        final List<BaseOBObject> lbobo = proQuery.list();
        for (final BaseOBObject b : lbobo) {
            final List<ApprovedVendor> avl = (List<ApprovedVendor>) b
                    .get("approvedVendorList");

            for (final ApprovedVendor rav : avl) {
                productIDandPriceBuy.put(b.getId().toString(), rav
                        .getListPrice().toString());
            }

        }
        return productIDandPriceBuy;

    }

    protected Document processInventory(String posId, String clientId,
            String orgId, EntityXMLConverter exc) {
        Document doc = null;
        String warehouseId = null;
        final StringBuilder defWclause = new StringBuilder();
        defWclause.append("client.id in ('0', " + clientId + ")");
        defWclause.append(" and organization.id in ('0', " + orgId + ")");

        final String extPosWhere = defWclause.toString() + " and searchKey="
                + posId;
        final OBQuery<BaseOBObject> obq1 = OBDal.getInstance().createQuery(
                "ExternalPOS", extPosWhere);
        obq1.setFilterOnReadableClients(false);
        obq1.setFilterOnReadableOrganization(false);

        if (obq1.list().isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            sb.append("ExternalPOS with ");
            sb.append(" CLIENT_ID=" + clientId);
            sb.append(", ORG_ID=" + orgId);
            sb.append(", POS_ID=" + posId + "not defined");
            throw new OBException(sb.toString());
        } else {
            final StringBuilder sPro = new StringBuilder();
            boolean incPro = false;
            for (final BaseOBObject b : obq1.list()) {
                final ExternalPOS ePos = (ExternalPOS) b;
                warehouseId = ePos.getWarehouse().getId();
                if (ePos.getIncludedProduct().equals("N")) {
                    incPro = true;
                    sPro.append("(''");
                    for (final ExternalPOSProduct p : ePos
                            .getExternalPOSProductList()) {
                        sPro.append(", '" + p.getProduct().getId() + "'");
                    }
                    sPro.append(")");
                }
            }

            final StringBuilder invWhere = new StringBuilder();
            invWhere.append("storageBin.id in (select lo.id from Locator lo");
            invWhere.append(" where lo.warehouse.id='" + warehouseId + "')");
            invWhere.append(" and uOM.id='100'");
            if (incPro) {
                invWhere.append(" and product.id in " + sPro);
            }
            invWhere.append(" and " + defWclause);

            final OBQuery<BaseOBObject> obq2 = OBDal.getInstance().createQuery(
                    "MaterialMgmtStorageDetail", invWhere.toString());
            obq2.setFilterOnReadableClients(false);
            obq2.setFilterOnReadableOrganization(false);
            final List<BaseOBObject> l1 = obq2.list();

            final Map<String, BigDecimal> keyUnits = new HashMap<String, BigDecimal>();
            final List<BaseOBObject> delIndex = new ArrayList<BaseOBObject>();
            final Map<String, String> keyStorageId = new HashMap<String, String>();
            int i = 0;
            for (final BaseOBObject b : l1) {
                final StorageDetail sd = (StorageDetail) b;
                final String proId = sd.getProduct().getId();
                final String attSet = sd.getAttributeSetValue().getId();
                final BigDecimal units = sd.getQuantityOnHand();
                final String key = proId + "_" + attSet;

                if (keyUnits.containsKey(key)) {
                    final BigDecimal sum = units.add(keyUnits.get(key));
                    keyUnits.put(key, sum);
                    delIndex.add(b);
                } else {
                    keyStorageId.put(key, sd.getId());
                    keyUnits.put(key, units);
                }
                i++;
            }

            l1.removeAll(delIndex);

            final StringWriter sw = new StringWriter();
            exc.setOutput(sw);
            exc.process(l1);
            final String xml2 = sw.toString();
            try {
                doc = DocumentHelper.parseText(xml2);
                for (final String s : keyStorageId.keySet()) {
                    final String stoId = keyStorageId.get(s);
                    final Element sel = (Element) doc
                            .selectSingleNode("/ob:Openbravo/MaterialMgmtStorageDetail[@id="
                                    + stoId + "]");
                    sel.element("quantityOnHand").setText(
                            keyUnits.get(s).toString());
                }

            } catch (final DocumentException e) {
                throw new OBException("Error parsing xml " + e.getMessage());
            }

            return doc;
        }
    }

    private void XMLDocumentFilter(Document doc, String entityName,
            List<String> filterNames) {
        final List<Element> lNodes = doc.selectNodes("/ob:Openbravo/"
                + entityName);
        for (final Element e : lNodes) {
            final List<Element> leee = e.elements();
            for (final Element e2 : leee) {
                if (!filterNames.contains(e2.getName())) {
                    e.remove(e2);
                }
            }
        }

    }

    private Document getBusinessPartnerInfo(Document doc) {
        final String entityLocation = "Location";

        // Search for Locations
        final List<Element> usedLocations = doc
                .selectNodes("/ob:Openbravo/BusinessPartner/businessPartnerLocationList/BusinessPartnerLocation[position()=1]");
        final StringBuilder aux = new StringBuilder();
        aux.append("''");
        for (final Element eLo : usedLocations) {
            aux
                    .append(" ,'"
                            + eLo.element("locationAddress").attributeValue(
                                    "id") + "'");
        }
        final String whereClause = " in (" + aux + ")";
        final OBQuery<BaseOBObject> obq3 = OBDal.getInstance().createQuery(
                entityLocation, "id" + whereClause);
        obq3.setFilterOnReadableClients(false);
        obq3.setFilterOnReadableOrganization(false);
        final EntityXMLConverter exc = EntityXMLConverter.newInstance();
        exc.setOptionEmbedChildren(true);
        exc.setOptionIncludeChildren(true);
        exc.setOptionIncludeReferenced(false);
        exc.setOptionExportClientOrganizationReferences(true);
        final StringWriter sw = new StringWriter();
        exc.setOutput(sw);
        exc.process(obq3.list());

        final String xml = sw.toString();
        Document doc2 = null;
        try {
            doc2 = DocumentHelper.parseText(xml);
        } catch (final DocumentException e1) {
            e1.printStackTrace();
        }

        final List<Element> lNodes = doc
                .selectNodes("/ob:Openbravo/BusinessPartner");
        for (final Element e : lNodes) {
            String firstname = null, lastname = null, email = null, postal = null;
            String phone = null, phone2 = null, fax = null, locationId = null;
            String address1 = null, address2 = null, city = null, region = null, country = null;
            // Get ADUser info
            final Element userList = e.element("aDUserList").element("ADUser");
            if (userList != null) {
                firstname = userList.elementText("firstname");
                lastname = userList.elementText("lastname");
                email = userList.elementText("email");
            }

            // Get BPLocation info
            final Element locationList = e.element(
                    "businessPartnerLocationList").element(
                    "BusinessPartnerLocation");

            if (locationList != null) {
                phone = locationList.elementText("phone");
                phone2 = locationList.elementText("phone2");
                fax = locationList.elementText("fax");
                locationId = locationList.element("locationAddress")
                        .attributeValue("id");
                // Get Location info

                final Element locEle = (Element) doc2
                        .selectSingleNode("/ob:Openbravo/Location[id='"
                                + locationId + "']");
                postal = locEle.elementText("postal");
                address1 = locEle.elementText("address1");
                address2 = locEle.elementText("address2");
                city = locEle.elementText("cityName");
                region = locEle.element("region").attributeValue("identifier");
                country = locEle.element("country")
                        .attributeValue("identifier");
            }

            // Add to XML
            final Map<String, String> m2 = new HashMap<String, String>();
            m2.put("firstname", firstname);
            m2.put("lastname", lastname);
            m2.put("email", email);
            m2.put("phone", phone);
            m2.put("phone2", phone2);
            m2.put("fax", fax);
            m2.put("address1", address1);
            m2.put("address2", address2);
            m2.put("city", city);
            m2.put("region", region);
            m2.put("country", country);
            m2.put("postal", postal);
            m2.put("visible", "1");
            m2.put("maxdebt", "0");
            addElementToXML(e, m2);
        }
        return doc;
    }

    private Map<String, String> processExternalPOS(Document doc, String erpPOS) {
        final StringBuilder whereProduct = new StringBuilder();
        final Map<String, String> result = new HashMap<String, String>();

        final List<Element> l2 = doc
                .selectNodes("/ob:Openbravo/ExternalPOS[searchKey=" + erpPOS
                        + "]");
        final Element rootElement = l2.get(0);
        // Get includeProduct
        final String incPro = rootElement.elementText("includedProduct");

        // Get Warehouse ID
        final String warehouseID = rootElement.element("warehouse")
                .attributeValue("id");

        // Get Price List ID
        final String priceList = rootElement.element("priceList")
                .attributeValue("id");

        if (incPro.equals("N")) {
            // Get Selected Products
            final List<Element> l = doc
                    .selectNodes("/ob:Openbravo/ExternalPOS/externalPOSProductList/ExternalPOSProduct");
            for (final Element e : l) {
                if (whereProduct.length() > 0) {
                    whereProduct.append(", ");
                }
                final String value = e.element("product").attributeValue("id");
                whereProduct.append("'" + value + "'");
                result.put(value, value);
            }
            result.put("allProducts", "no");
        } else {
            result.put("allProducts", "yes");
        }

        final String whereProductClause = (whereProduct.length() == 0) ? null
                : "id in ( " + whereProduct.toString() + ")";
        result.put("priceListId", priceList);
        result.put("whereProduct", whereProductClause);
        result.put("warehouseId", warehouseID);
        return result;
    }

    private void addElementToXML(Element e, Map<String, String> nameValue) {
        final Set<String> keys = nameValue.keySet();
        for (final String k : keys) {
            if (nameValue.get(k) == null || nameValue.get(k).isEmpty()) {
                e.addElement(k);
            } else {
                e.addElement(k).setText(nameValue.get(k));
            }
        }
    }

    private void setFilterBusinessPartner() {
        filterBP = new ArrayList<String>();
        filterBP.add("client");
        filterBP.add("organization");
        filterBP.add("id");
        filterBP.add("searchKey");
        filterBP.add("taxID");
        filterBP.add("name");
        filterBP.add("firstname");
        filterBP.add("lastname");
        filterBP.add("email");
        filterBP.add("description");
        filterBP.add("phone");
        filterBP.add("phone2");
        filterBP.add("fax");
        filterBP.add("postal");
        filterBP.add("address1");
        filterBP.add("address2");
        filterBP.add("city");
        filterBP.add("region");
        filterBP.add("country");
        filterBP.add("soTaxCategory");
        filterBP.add("visible");
        filterBP.add("maxdebt");
    }

    private void setFilterProduct() {
        filterProduct = new ArrayList<String>();
        filterProduct.add("client");
        filterProduct.add("organization");
        filterProduct.add("id");
        filterProduct.add("uPC");
        filterProduct.add("name");
        filterProduct.add("productCategory");
        filterProduct.add("taxCategory");
        filterProduct.add("imageURL");
        filterProduct.add("attributeSet");
        filterProduct.add("priceSell");
        filterProduct.add("priceBuy");
        filterProduct.add("warehouse");
    }

    private void setFilterProductCategory() {
        filterProductCategory = new ArrayList<String>();
        filterProductCategory.add("client");
        filterProductCategory.add("organization");
        filterProductCategory.add("id");
        filterProductCategory.add("value");
    }

    private void setFilterWarehouse() {
        filterWarehouse = new ArrayList<String>();
        filterWarehouse.add("id");
        filterWarehouse.add("name");
        filterWarehouse.add("location");
    }

    public void doDelete(String path, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        // TODO Auto-generated method stub

    }

    public void doPost(String path, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        // TODO Auto-generated method stub

    }

    public void doPut(String path, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        // TODO Auto-generated method stub

    }
}
