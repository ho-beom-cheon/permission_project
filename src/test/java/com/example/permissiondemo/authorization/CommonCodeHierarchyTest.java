package com.example.permissiondemo.authorization;
import static org.assertj.core.api.Assertions.*;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.web.ApiException;
import org.junit.jupiter.api.Test;

class CommonCodeHierarchyTest {
    @Test void identicalChildCodesInDifferentDetailsAreIndependentAndParentDisablePropagates(){
        var codes=new CommonCodeService();
        codes.saveItem("REGION","SAME","서울 하위","SEOUL",1,true,null,null);
        codes.saveItem("REGION","SAME","부산 하위","BUSAN",1,true,null,null);
        assertThat(codes.findItems("REGION",true,"SEOUL")).filteredOn(i->i.code().equals("SAME")).extracting(CommonCodeService.CommonCodeItem::name).containsExactly("서울 하위");
        assertThat(codes.findItems("REGION",true,"BUSAN")).filteredOn(i->i.code().equals("SAME")).extracting(CommonCodeService.CommonCodeItem::name).containsExactly("부산 하위");
        long version=codes.groupVersions().get("REGION");
        assertThatThrownBy(()->codes.deleteItem("REGION","SEOUL",null,version)).isInstanceOf(ApiException.class);
        codes.saveItem("REGION","SEOUL","서울",10,false);
        assertThat(codes.findItems("REGION",true,"SEOUL")).isEmpty();
        assertThat(codes.findItems("REGION",true,"BUSAN")).isNotEmpty();
        assertThatThrownBy(()->codes.deleteItem("REGION","SAME","BUSAN",version)).isInstanceOf(ApiException.class);
    }
    @Test void menuGrantRemovalAlsoClearsActionsSoRegrantDoesNotRestoreOldPrivileges(){
        var catalog=new AuthorizationCatalog();
        assertThat(catalog.allActionGrants().get("AUTH_CONTENT_MANAGER")).isNotEmpty();
        catalog.setMenuGrant("AUTH_CONTENT_MANAGER","CONTENT_LIST",false);
        assertThat(catalog.allActionGrants().get("AUTH_CONTENT_MANAGER")).noneMatch(key->key.menuId().equals("CONTENT_LIST"));
        catalog.setMenuGrant("AUTH_CONTENT_MANAGER","CONTENT_LIST",true);
        assertThat(catalog.allActionGrants().get("AUTH_CONTENT_MANAGER")).noneMatch(key->key.menuId().equals("CONTENT_LIST"));
    }
}
