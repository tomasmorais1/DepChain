// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// @title IST Coin
/// @notice ERC-20 with guarded approve against approval frontrunning.
contract ISTCoin {
    string public constant name = "IST Coin";
    string public constant symbol = "IST";
    uint8 public constant decimals = 2;
    uint256 public constant totalSupply = 100_000_000 * 10 ** uint256(decimals);

    mapping(address => uint256) private _balances;
    mapping(address => mapping(address => uint256)) private _allowances;

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);

    constructor() {
        _balances[msg.sender] = totalSupply;
        emit Transfer(address(0), msg.sender, totalSupply);
    }

    function balanceOf(address account) external view returns (uint256) {
        return _balances[account];
    }

    function allowance(
        address owner,
        address spender
    ) external view returns (uint256) {
        return _allowances[owner][spender];
    }

    function transfer(address to, uint256 value) external returns (bool) {
        _transfer(msg.sender, to, value);
        return true;
    }

    /// @notice Guarded approval flow:
    /// a non-zero allowance must be reset to zero before setting a new non-zero value.
    function approve(address spender, uint256 value) external returns (bool) {
        uint256 current = _allowances[msg.sender][spender];
        require(
            value == 0 || current == 0,
            "IST: reset allowance to 0 first"
        );
        _allowances[msg.sender][spender] = value;
        emit Approval(msg.sender, spender, value);
        return true;
    }

    function increaseAllowance(
        address spender,
        uint256 addedValue
    ) external returns (bool) {
        uint256 newAllowance = _allowances[msg.sender][spender] + addedValue;
        _allowances[msg.sender][spender] = newAllowance;
        emit Approval(msg.sender, spender, newAllowance);
        return true;
    }

    function decreaseAllowance(
        address spender,
        uint256 subtractedValue
    ) external returns (bool) {
        uint256 current = _allowances[msg.sender][spender];
        require(current >= subtractedValue, "IST: decreased below zero");
        uint256 newAllowance = current - subtractedValue;
        _allowances[msg.sender][spender] = newAllowance;
        emit Approval(msg.sender, spender, newAllowance);
        return true;
    }

    function transferFrom(
        address from,
        address to,
        uint256 value
    ) external returns (bool) {
        uint256 currentAllowance = _allowances[from][msg.sender];
        require(currentAllowance >= value, "IST: insufficient allowance");

        _allowances[from][msg.sender] = currentAllowance - value;
        emit Approval(from, msg.sender, _allowances[from][msg.sender]);

        _transfer(from, to, value);
        return true;
    }

    function _transfer(address from, address to, uint256 value) internal {
        require(to != address(0), "IST: invalid recipient");
        require(_balances[from] >= value, "IST: insufficient balance");

        _balances[from] -= value;
        _balances[to] += value;

        emit Transfer(from, to, value);
    }
}
