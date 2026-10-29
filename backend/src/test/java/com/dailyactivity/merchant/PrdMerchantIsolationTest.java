package com.dailyactivity.merchant;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.commission.application.CommissionService;
import com.dailyactivity.commission.domain.port.CommissionRepository;
import com.dailyactivity.export.application.ExportService;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.identity.application.CustomerService;
import com.dailyactivity.identity.application.MemberService;
import com.dailyactivity.merchant.application.MerchantService;
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
        var order=pending(buyer,data.productId(),1);
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
    void bothMerchantsConsumeTheSameStockWithoutPublicManagementGrants() {
        var b=other();
        jdbc.update("UPDATE merchant_permission SET deleted=id WHERE merchant_id=?",b.seller().merchantId());
        int before=jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?",Integer.class,data.productId());
        pending(data,data.productId(),2);
        pending(b,data.productId(),3);
        assertThat(jdbc.queryForObject("SELECT stock_available FROM product WHERE id=?",Integer.class,data.productId())).isEqualTo(before-5);
        assertThatThrownBy(()->accounts.requireMenu(b.seller(),"products")).isInstanceOf(ApiException.class);
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
        assertThat(payments.customerChannels(data.customer())).extracting(item->item.get("id")).contains(data.channelId()).doesNotContain(b.channelId());
        assertThatThrownBy(()->orders.submitPayment(data.customer(),orderId(order),new OrderService.PaymentRequest(b.channelId(),"test-transfer",(BigDecimal)order.get("total"),(java.time.Instant)order.get("updatedAt"),BigDecimal.ONE))).isInstanceOf(ApiException.class);
        String qr=jdbc.queryForObject("SELECT qr_file_uuid FROM payment_channel WHERE id=?",String.class,b.channelId());
        assertThatThrownBy(()->files.signedUrl(qr,data.seller())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->payments.create(data.seller(),new PaymentService.PaymentRequest("Foreign QR","USD","TEST","test-address",null,qr,true,1))).isInstanceOf(ApiException.class);
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
    void managerCanCreateMerchantButCannotAccessItsOrdersAndRevocationClearsMemberGrants() {
        jdbc.update("UPDATE `user` SET merchant_management=1 WHERE id=?",data.sellerId());
        var b=other();
        var order=approved(b);
        assertThatThrownBy(()->orders.detail(data.seller(),orderId(order))).isInstanceOf(ApiException.class);
        var existing=merchantRepository.find(b.seller().merchantId()).orElseThrow();
        merchants.update(data.seller(),existing.id(),new MerchantService.Update(existing.name(),true,existing.shippingFee(),List.of(),existing.updatedAt()));
        assertThatThrownBy(()->accounts.requireMenu(b.seller(),"products")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permission WHERE user_id=? AND menu_code IN ('products','certificates','categories') AND deleted=0",Long.class,b.sellerId())).isZero();
        var created=merchants.create(data.seller(),new MerchantService.Create("NEW"+UUID.randomUUID().toString().substring(0,8),"New merchant",new BigDecimal("7.50"),List.of("products"),"new-"+UUID.randomUUID(),"Initial admin",PrdTestFixture.SELLER_PASSWORD,PrdTestFixture.SELLER_PASSWORD));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE merchant_id=? AND user_type='seller' AND merchant_management=0",Long.class,created.id())).isEqualTo(1);
        assertThat(created.permissions()).containsExactly("products");
    }
    @Test
    void lastMerchantManagerCannotDisableItsOwnMerchant() {
        jdbc.update("UPDATE `user` SET merchant_management=1 WHERE id=?",data.sellerId());
        var m=merchantRepository.find(data.seller().merchantId()).orElseThrow();
        assertThatThrownBy(()->merchants.update(data.seller(),m.id(),new MerchantService.Update(m.name(),false,m.shippingFee(),List.of("products","certificates","categories"),m.updatedAt())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("LAST_MERCHANT_MANAGER");
    }
    @Test
    void disabledMerchantBlocksBothRolesAndReenableDoesNotRestoreOldTokens() {
        jdbc.update("UPDATE `user` SET merchant_management=1 WHERE id=?",data.sellerId());
        var b=other();
        var m=merchantRepository.find(b.seller().merchantId()).orElseThrow();
        merchants.update(data.seller(),m.id(),new MerchantService.Update(m.name(),false,m.shippingFee(),List.of("products"),m.updatedAt()));
        assertThatThrownBy(()->accounts.assertValid(b.seller())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->accounts.assertValid(b.customer())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->accounts.customerLogin(b.customerEmail(),PrdTestFixture.CUSTOMER_PASSWORD)).isInstanceOf(ApiException.class);
        var disabled=merchantRepository.find(m.id()).orElseThrow();
        merchants.update(data.seller(),m.id(),new MerchantService.Update(m.name(),true,m.shippingFee(),List.of("products"),disabled.updatedAt()));
        assertThatThrownBy(()->accounts.assertValid(b.customer())).isInstanceOf(ApiException.class);
        assertThat(accounts.customerLogin(b.customerEmail(),PrdTestFixture.CUSTOMER_PASSWORD)).containsKey("accessToken");
    }

    @Test
    void ordinaryMemberManagerCannotTakeOverDesignatedManagementAccount() {
        jdbc.update("UPDATE `user` SET merchant_management=1 WHERE id=?",data.sellerId());
        var ordinary=PrdTestFixture.seedRestrictedSeller(jdbc,data.sellerId());
        jdbc.update("INSERT INTO user_permission(user_id,menu_code) VALUES(?,'members')",ordinary.userId());
        var replacement=new MemberService.MemberRequest(data.sellerAccount(),"Replaced",PrdTestFixture.SELLER_PASSWORD,
                PrdTestFixture.SELLER_PASSWORD,List.of("members"));
        assertThatThrownBy(()->members.update(ordinary,data.sellerId(),replacement)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->members.status(ordinary,data.sellerId(),false)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id=?",String.class,data.sellerId())).isEqualTo("Test Admin");
    }

    @Test
    void designatedManagerIsMarkedInMemberListAndCannotEditOrDisableSelf() {
        jdbc.update("UPDATE `user` SET merchant_management=1 WHERE id=?", data.sellerId());
        assertThat(members.page(data.seller(), data.sellerAccount(), 1).items())
                .filteredOn(member -> member.id() == data.sellerId())
                .singleElement().extracting(member -> member.merchantManagement()).isEqualTo(true);
        var replacement = new MemberService.MemberRequest(data.sellerAccount(), "Changed", null, null, List.of("members"));
        assertThatThrownBy(() -> members.update(data.seller(), data.sellerId(), replacement))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> members.status(data.seller(), data.sellerId(), false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id=?", String.class, data.sellerId())).isEqualTo("Test Admin");
        assertThat(jdbc.queryForObject("SELECT active FROM `user` WHERE id=?", Boolean.class, data.sellerId())).isTrue();
    }

    @Test
    void accountIdentifiersStayGlobalWhilePaymentChannelNamesAreMerchantLocal() {
        var b=other();
        assertThatThrownBy(()->customers.create(b.seller(),new CustomerService.CustomerRequest(data.customerEmail(),"Duplicate",
                PrdTestFixture.CUSTOMER_PASSWORD,PrdTestFixture.CUSTOMER_PASSWORD,"regular","V1",null,null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CUSTOMER_EMAIL_EXISTS");
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
    void changingMerchantShippingFeeInvalidatesOldQuotes() {
        carts.set(data.customer(),data.productId(),1);
        var q=orders.quote(data.customer(),new OrderService.QuoteRequest(data.addressId(),List.of(new OrderService.CartRequest(data.productId(),1))));
        var m=merchantRepository.find(data.seller().merchantId()).orElseThrow();
        merchants.settings(data.seller(),new MerchantService.Settings(new BigDecimal("25.00"),m.updatedAt()));
        assertThatThrownBy(()->orders.create(data.customer(),new OrderService.CreateRequest((String)q.get("quoteToken"),"test",List.of(new OrderService.CartRequest(data.productId(),1)),data.addressId())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTE_CHANGED");
    }
}
