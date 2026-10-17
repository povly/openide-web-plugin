function getUser(id) {
    const name/*<# : string #>*/ = fetchName(id);
    return { id, name };
}
