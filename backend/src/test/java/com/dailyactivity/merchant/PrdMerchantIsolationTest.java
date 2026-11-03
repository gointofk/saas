package com.dailyactivity.merchant;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.catalog.application.CatalogService;
import com.dailyactivity.export.application.ExportService;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.identity.application.CustomerService;
import com.dailyactivity.identity.application.MemberService;
import com.dailyactivity.merchant.application.MerchantService;
import com.dailyactivity.merchant.domain.ShippingFees;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.payment.application.PaymentService;
import com.dailyactivity.support.PrdDatabaseTest;
import com.dailyactivity.support.PrdTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PrdMerchantIsolationTest extends PrdDatabaseTest {
    @Autowired AccountService accounts;
    @Autowired CatalogService catalog;
    @Autowired CustomerService customers;
    @Autowired MemberService members;
    @Autowired MerchantService merchants;
    @Autowired MerchantRepository merchantRepository;
    @Autowired PaymentService payments;
    @Autowired FileService files;
    @Autowired CommissionService commissions;
    @Autowired ExportService exports;

    private PrdTestFixture.Scenario other() {
        return PrdTestFixture.seed(jdbc,PrdTestFixture.merchant(jdbc,"B"+UUID.randomUUID().toString().replace("-", "").substring(0,25)));
    }
    private Map<String,Object> pending(PrdTestFixture.Scenario buyer,long productId,int quantity) {
        carts.set(buyer.customer(),productId,quantity);
        var quote=orders.quote(buyer.customer(),new OrderService.QuoteRequest(buyer.addressId(),List.of(new OrderService.CartRequest(productId,quantity))));
        return orders.create(buyer.customer(),new OrderService.CreateRequest((String)quote.get("quoteToken"),"Merchant isolation test",List.of(new OrderService.CartRequest(productId,quantity)),buyer.addressId()));
    }
    private Map<String,Object> approved(PrdTestFixture.Scenario buyer) {
        var order=pending(buyer,buyer.productId(),1);
        return orders.decideAudit(buyer.seller(),orderId(order),new OrderService.AuditDecision("approved","Test",null));
    }
    @Test
    void orderOwnershipFollowsBuyerAndForeignReviewIsRejected() {
        var b=other();
        var order=pending(data,data.productId(),1);
        long id=orderId(order);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM sales_order WHERE id=?",Long.class,id)).isEqualTo(data.seller().merchantId());
        assertThatThrownBy(()->orders.auditDetail(b.seller(),id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->orders.decideAudit(b.seller(),id,new OrderService.AuditDecision("approved","Foreign",null))).isInstanceOf(ApiException.class);
        assertThat(orders.auditOrders(b.seller(),"pending",null,1).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT audit_status FROM sales_order WHERE id=?",String.class,id)).isEqualTo("pending");
    }
    @Test
    void eachMerchantHasItsOwnCatalogAndStock() {
        var b=other();
        int stockA=jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?",Integer.class,data.productId());
        int stockB=jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?",Integer.class,b.productId());
        pending(data,data.productId(),2);
        pending(b,b.productId(),3);
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?",Integer.class,data.productId())).isEqualTo(stockA-2);
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?",Integer.class,b.productId())).isEqualTo(stockB-3);
        assertThatThrownBy(()->carts.set(b.customer(),data.productId(),1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->catalog.setProductActive(b.seller(),data.productId(),false)).isInstanceOf(ApiException.class);
        assertThat(catalog.customerProducts(data.customer()).stream().map(item->((Number)item.get("id")).longValue()))
                .contains(data.productId()).doesNotContain(b.productId());
        assertThat(catalog.customerProducts(b.customer()).stream().map(item->((Number)item.get("id")).longValue()))
                .contains(b.productId()).doesNotContain(data.productId());
    }

    @Test
    void categoriesAndSkusAreUniqueWithinAMerchantAndCrossTenantIdsCannotBeEdited() {
        var b=other();
        String sharedName=jdbc.queryForObject("SELECT name FROM category WHERE id=?",String.class,data.categoryId());
        long bCategory=((Number)catalog.createCategory(b.seller(),new CatalogService.CategoryRequest(sharedName,1,true)).get("id")).longValue();
        long bProduct=((Number)catalog.createProduct(b.seller(),new CatalogService.ProductRequest(
                bCategory,data.productSku(),"B's private copy","","regular",true,new BigDecimal("55.00"),null,5,List.of())).get("id")).longValue();

        assertThat(catalog.adminCategories(data.seller()).stream().map(item->((Number)item.get("id")).longValue()))
                .contains(data.categoryId()).doesNotContain(bCategory);
        assertThat(catalog.adminCategories(b.seller()).stream().map(item->((Number)item.get("id")).longValue()))
                .contains(bCategory).doesNotContain(data.categoryId());
        assertThat(catalog.adminProducts(data.seller(),data.productSku(),null,1).items()).extracting("id").containsExactly(data.productId());
        assertThat(catalog.adminProducts(b.seller(),data.productSku(),null,1).items()).extracting("id").containsExactly(bProduct);
        assertThatThrownBy(()->catalog.setProductActive(b.seller(),data.productId(),false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->catalog.createProduct(b.seller(),new CatalogService.ProductRequest(
                data.categoryId(),"FOREIGN-CATEGORY-"+UUID.randomUUID(),"Invalid","","regular",true,BigDecimal.ONE,null,1,List.of())))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT active FROM product WHERE id=?",Boolean.class,data.productId())).isTrue();
    }
    @Test
    void privateListsDetailsStatisticsAndExportExcludeTheOtherMerchant() {
        var b=other();
        var aOrder=approved(data);
        var bOrder=approved(b);
        assertThat(orders.adminOrders(data.seller(),null,null,null,null,null,1).total()).isEqualTo(1);
        assertThatThrownBy(()->orders.detail(data.seller(),orderId(bOrder))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->customers.get(data.seller(),b.customerId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->members.status(data.seller(),b.sellerId(),false)).isInstanceOf(ApiException.class);
        var request=new ExportService.ExportRequest(Map.of());
        assertThat(exports.preview(data.seller(),"orders",request).recordCount()).isEqualTo(1);
        assertThat(new String(exports.export(data.seller(),"orders",request).content(),java.nio.charset.StandardCharsets.UTF_8))
                .contains((String)aOrder.get("orderNo")).doesNotContain((String)bOrder.get("orderNo"));
    }
    @Test
    void paymentChannelAndQrCannotBeUsedAcrossMerchants() {
        var b=other();
        var order=approved(data);
        assertThat(customerChannels(data.customer())).extracting(item->item.get("id")).contains(data.channelId()).doesNotContain(b.channelId());
        assertThatThrownBy(()->orders.submitPayment(data.customer(),orderId(order),new OrderService.PaymentRequest(b.channelId(),"test-transfer",(BigDecimal)order.get("total"),(java.time.Instant)order.get("updatedAt"),BigDecimal.ONE))).isInstanceOf(ApiException.class);
        String qr=jdbc.queryForObject("SELECT qr_file_uuid FROM payment_channel WHERE id=?",String.class,b.channelId());
        assertThatThrownBy(()->files.signedUrl(qr,data.seller())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->payments.create(data.seller(),new PaymentService.PaymentRequest("Foreign QR","USD","TEST","test-address",null,qr,true,1))).isInstanceOf(ApiException.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> customerChannels(com.dailyactivity.auth.domain.Principal customer) {
        return (List<Map<String, Object>>) payments.customerPaymentOptions(customer).get("channels");
    }
    @Test
    void referralMustRemainInsideTheBuyersMerchant() {
        var b=other();
        assertThatThrownBy(()->customers.update(data.seller(),data.customerId(),new CustomerService.CustomerRequest(data.customerEmail(),"Customer",null,null,"regular","V1",b.referrerId(),new BigDecimal("5")))).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT referrer_id FROM `user` WHERE id=?",Long.class,data.customerId())).isEqualTo(data.referrerId());
    }
    @Test
    void mixedMerchantSettlementFailsWithoutSettlingLocalRows() {
        var b=other(); var aOrder=approved(data); var bOrder=approved(b);
        var items=new ArrayList<CommissionRepository.SettlementRequest>();
        for(var order:List.of(aOrder,bOrder)) {
            long accrual=jdbc.queryForObject("SELECT id FROM commission_accrual WHERE order_id=?",Long.class,orderId(order));
            BigDecimal amount=jdbc.queryForObject("SELECT commission_amount FROM commission_accrual WHERE id=?",BigDecimal.class,accrual);
            items.add(new CommissionRepository.SettlementRequest(accrual,amount));
        }
        assertThatThrownBy(()->commissions.settle(data.seller(),new CommissionService.SettlementRequest(items,"test",true))).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_settlement WHERE merchant_id=?",Long.class,data.seller().merchantId())).isZero();
    }
    @Test
    void managerCanCreateMerchantButCannotAccessItsOrdersAndNewAdminReceivesMerchantMenus() {
        var platform=PrdTestFixture.platform(jdbc);
        var b=other();
        var order=approved(b);
        assertThatThrownBy(()->orders.detail(platform,orderId(order))).isInstanceOf(ApiException.class);
        var existing=merchantRepository.find(b.seller().merchantId()).orElseThrow();
        merchants.update(platform,existing.id(),new MerchantService.Update(existing.name(),true,existing.shippingFees(),existing.updatedAt(),existing.domain(),existing.logoUrl(),existing.theme()));
        assertThatCode(()->accounts.requireMenu(b.seller(),"products")).doesNotThrowAnyException();
        var created=merchants.create(platform,new MerchantService.Create("NEW"+UUID.randomUUID().toString().substring(0,8),"New merchant",ShippingFees.uniform(new BigDecimal("7.50")),"new-"+UUID.randomUUID(),"Initial admin",PrdTestFixture.SELLER_PASSWORD,PrdTestFixture.SELLER_PASSWORD,"new-"+UUID.randomUUID()+".test","/ytpeps-logo.svg","classic",false,null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller WHERE merchant_id=?",Long.class,created.id())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT menu_code FROM seller_permission WHERE seller_id=(SELECT id FROM seller WHERE merchant_id=? AND deleted=0) AND deleted=0",String.class,created.id()))
                .contains("products","certificates","categories");
    }
    @Test
    void disabledMerchantBlocksBothRolesAndReenableDoesNotRestoreOldTokens() {
        var platform=PrdTestFixture.platform(jdbc);
        var b=other();
        var m=merchantRepository.find(b.seller().merchantId()).orElseThrow();
        merchants.update(platform,m.id(),new MerchantService.Update(m.name(),false,m.shippingFees(),m.updatedAt(),m.domain(),m.logoUrl(),m.theme()));
        assertThatThrownBy(()->accounts.assertValid(b.seller())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->accounts.assertValid(b.customer())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->accounts.customerLogin(b.customer().merchantId(), b.customerEmail(),PrdTestFixture.CUSTOMER_PASSWORD)).isInstanceOf(ApiException.class);
        var disabled=merchantRepository.find(m.id()).orElseThrow();
        merchants.update(platform,m.id(),new MerchantService.Update(m.name(),true,m.shippingFees(),disabled.updatedAt(),disabled.domain(),disabled.logoUrl(),disabled.theme()));
        assertThatThrownBy(()->accounts.assertValid(b.customer())).isInstanceOf(ApiException.class);
        assertThat(accounts.customerLogin(b.customer().merchantId(), b.customerEmail(),PrdTestFixture.CUSTOMER_PASSWORD)).containsKey("accessToken");
    }

    @Test
    void customerEmailsAreMerchantLocalWhileSellerAccountsRemainGlobal() {
        var b=other();
        assertThatCode(()->customers.create(b.seller(),new CustomerService.CustomerRequest(data.customerEmail(),"Duplicate",
                PrdTestFixture.CUSTOMER_PASSWORD,PrdTestFixture.CUSTOMER_PASSWORD,"regular","V1",null,null)))
                .doesNotThrowAnyException();
        var localLogin=accounts.customerLogin(data.customer().merchantId(),data.customerEmail(),PrdTestFixture.CUSTOMER_PASSWORD);
        var foreignLogin=accounts.customerLogin(b.customer().merchantId(),data.customerEmail(),PrdTestFixture.CUSTOMER_PASSWORD);
        assertThat(((Map<?,?>)localLogin.get("customer")).get("id")).isNotEqualTo(((Map<?,?>)foreignLogin.get("customer")).get("id"));
        assertThatThrownBy(()->members.create(b.seller(),new MemberService.MemberRequest(data.sellerAccount(),"Duplicate",
                PrdTestFixture.SELLER_PASSWORD,PrdTestFixture.SELLER_PASSWORD,List.of("orders"))))
                .isInstanceOf(ApiException.class);
        String name=jdbc.queryForObject("SELECT name FROM payment_channel WHERE id=?",String.class,data.channelId());
        String qr=jdbc.queryForObject("SELECT qr_file_uuid FROM payment_channel WHERE id=?",String.class,b.channelId());
        assertThatCode(()->payments.create(b.seller(),new PaymentService.PaymentRequest(name,"USD","TEST","test-address",null,qr,true,2)))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_channel WHERE name=?",Long.class,name)).isEqualTo(2);
    }

    @Test
    void changingMerchantTierShippingFeeInvalidatesOldQuotes() {
        carts.set(data.customer(),data.productId(),1);
        var q=orders.quote(data.customer(),new OrderService.QuoteRequest(data.addressId(),List.of(new OrderService.CartRequest(data.productId(),1))));
        var m=merchantRepository.find(data.seller().merchantId()).orElseThrow();
        var fees = new ShippingFees(new BigDecimal("25.00"), new BigDecimal("5.00"), new BigDecimal("20.00"),
                new BigDecimal("20.00"), new BigDecimal("20.00"), new BigDecimal("20.00"));
        merchants.update(PrdTestFixture.platform(jdbc),m.id(),new MerchantService.Update(m.name(),true,fees,m.updatedAt(),m.domain(),m.logoUrl(),m.theme()));
        assertThat(merchantRepository.find(m.id()).orElseThrow().shippingFees()).isEqualTo(fees);
        assertThatThrownBy(()->orders.create(data.customer(),new OrderService.CreateRequest((String)q.get("quoteToken"),"test",List.of(new OrderService.CartRequest(data.productId(),1)),data.addressId())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTE_CHANGED");
    }
    @Test
    void duplicateDomainIsRejected() {
        var b=other();var m=merchantRepository.find(b.seller().merchantId()).orElseThrow();
        var existing=merchantRepository.find(data.seller().merchantId()).orElseThrow();
        assertThatThrownBy(()->merchants.update(PrdTestFixture.platform(jdbc),m.id(),
                new MerchantService.Update("Changed",true,ShippingFees.uniform(BigDecimal.ONE),m.updatedAt(),existing.domain(),m.logoUrl(),m.theme())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MERCHANT_DOMAIN_EXISTS");
    }

}
