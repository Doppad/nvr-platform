package com.nvr.nvrservice.repo;

import com.nvr.nvrservice.domain.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AddressRepo extends JpaRepository<Address, Long> {

    List<Address> findByOwnerId(Long ownerId);
}
