package xiaozhi.modules.security.service.impl;

import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;
import xiaozhi.modules.externalidentity.ExternalIdentityService;
import xiaozhi.modules.security.dao.SysUserTokenDao;
import xiaozhi.modules.security.entity.SysUserTokenEntity;
import xiaozhi.modules.security.service.ShiroService;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;

@Service
public class ShiroServiceImpl implements ShiroService {
    private final SysUserDao sysUserDao;
    private final SysUserTokenDao sysUserTokenDao;
    private ExternalIdentityService external;
    public ShiroServiceImpl(SysUserDao users,SysUserTokenDao tokens){this.sysUserDao=users;this.sysUserTokenDao=tokens;}
    @Autowired(required=false) public void setExternalIdentity(ExternalIdentityService service){external=service;}

    @Override
    public SysUserTokenEntity getByToken(String token) {
        if(ExternalIdentityService.externalToken(token)) {
            if(external==null)return null;
            var principal=external.authenticate(token);
            SysUserTokenEntity value=new SysUserTokenEntity();value.setId(principal.tokenId());value.setUserId(principal.userId());
            value.setToken(token);value.setExpireDate(java.util.Date.from(principal.expiresAt()));return value;
        }
        return sysUserTokenDao.getByToken(token);
    }

    @Override
    public SysUserEntity getUser(Long userId) {
        return sysUserDao.selectById(userId);
    }
}